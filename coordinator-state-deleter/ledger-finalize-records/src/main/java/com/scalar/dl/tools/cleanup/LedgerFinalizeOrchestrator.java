package com.scalar.dl.tools.cleanup;

import com.google.common.annotations.VisibleForTesting;
import com.scalar.db.api.DistributedStorageAdmin;
import com.scalar.db.api.DistributedTransactionManager;
import com.scalar.db.config.DatabaseConfig;
import com.scalar.db.service.StorageFactory;
import com.scalar.db.service.TransactionFactory;
import com.scalar.db.transaction.consensuscommit.Coordinator;
import com.scalar.db.util.ScalarDbUtils;
import com.scalar.dl.tools.common.CompletionToken;
import com.scalar.dl.tools.common.CoordinatorStateDeleterException;
import com.scalar.dl.tools.common.LedgerConfigValidator;
import com.scalar.dl.tools.common.StorageValidator;
import com.scalar.dl.tools.scan.ResumableScanner;
import com.scalar.dl.tools.scan.ResumableScannerFactory;
import com.scalar.dl.tools.scan.ScanResult;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrates the {@code ledger-finalize-records} workflow.
 *
 * <p>This class discovers all transactional tables, scans each table using a resumable scanner, and
 * finalizes every record still in a non-terminal state (PREPARED or DELETED) whose {@code
 * tx_prepared_at} is before the guarantee timestamp. On completion, it emits a {@link
 * CompletionToken} that is later consumed by {@code coordinator-state-cleanup}.
 *
 * <p>The workflow is resumable: progress is checkpointed per table, so a failure only requires
 * re-invocation with the same checkpoint directory. The start timestamp and the target table set
 * are captured once on the first invocation and reused across resumptions.
 */
public final class LedgerFinalizeOrchestrator implements AutoCloseable {

  private static final Logger logger = LoggerFactory.getLogger(LedgerFinalizeOrchestrator.class);

  private final DistributedStorageAdmin admin;
  private final DistributedTransactionManager txManager;
  private final ResumableScannerFactory scannerFactory;
  private final Path checkpointDir;

  @VisibleForTesting
  LedgerFinalizeOrchestrator(
      DistributedStorageAdmin admin,
      DistributedTransactionManager txManager,
      ResumableScannerFactory scannerFactory,
      Path checkpointDir) {
    this.admin = admin;
    this.txManager = txManager;
    this.scannerFactory = scannerFactory;
    this.checkpointDir = checkpointDir;
  }

  /**
   * Creates an orchestrator.
   *
   * @param props the properties used by the ScalarDL Ledger
   * @param checkpointDir root directory for checkpoint state
   * @return a new orchestrator instance
   * @throws CoordinatorStateDeleterException if the configured storage is not supported
   */
  public static LedgerFinalizeOrchestrator create(Properties props, Path checkpointDir) {
    DistributedStorageAdmin admin = null;
    DistributedTransactionManager txManager = null;
    try {
      DatabaseConfig databaseConfig = new DatabaseConfig(props);
      StorageValidator.validate(databaseConfig);
      LedgerConfigValidator.validate(databaseConfig);
      StorageFactory storageFactory = StorageFactory.create(props);
      admin = storageFactory.getStorageAdmin();
      txManager = TransactionFactory.create(props).getTransactionManager();
      ResumableScannerFactory scannerFactory =
          new ResumableScannerFactory(new DatabaseConfig(props));
      return new LedgerFinalizeOrchestrator(admin, txManager, scannerFactory, checkpointDir);
    } catch (Exception e) {
      if (txManager != null) {
        txManager.close();
      }
      if (admin != null) {
        admin.close();
      }
      throw e;
    }
  }

  /**
   * Executes the full orchestration workflow: load or initialize state, scan each table, finalize
   * non-terminal records, and emit a completion token.
   *
   * @return the base64url-encoded completion token
   * @throws Exception if any table processing or state persistence fails
   */
  public String execute() throws Exception {
    LedgerFinalizeStateManager stateManager = new LedgerFinalizeStateManager(checkpointDir);
    LedgerFinalizeState state = loadOrInitializeState(stateManager);

    long startedAtMs = state.getStartedAtMs();
    RecordStateChecker stateChecker = new RecordStateChecker(startedAtMs);

    for (String qualifiedTable : state.getTableList()) {
      if (state.getCompletedTables().contains(qualifiedTable)) {
        logger.info("Skipping already completed table: {}", qualifiedTable);
        continue;
      }
      processTable(stateManager, state, qualifiedTable, stateChecker);
    }

    String completionToken =
        CompletionToken.create(CompletionToken.ServerType.LEDGER, startedAtMs).encode();
    logger.info("Completion token generated successfully: {}", completionToken);
    return completionToken;
  }

  /**
   * Loads persisted state if available; otherwise captures the start timestamp, discovers all
   * transactional tables via the Admin API, and persists the initial state.
   */
  private LedgerFinalizeState loadOrInitializeState(LedgerFinalizeStateManager stateManager)
      throws Exception {
    LedgerFinalizeState state = stateManager.load();
    if (state != null) {
      logger.info(
          "Found existing checkpoint data; resuming the previous run started at {}. "
              + "{} of {} target tables have already been processed.",
          Instant.ofEpochMilli(state.getStartedAtMs()),
          state.getCompletedTables().size(),
          state.getTableList().size());
      return state;
    }

    long startedAtMs = System.currentTimeMillis();
    List<String> tableNames = discoverTables();
    state = new LedgerFinalizeState(startedAtMs, tableNames);
    stateManager.persist(state);
    logger.info(
        "Starting a new run at {}. {} target tables were found: {}",
        Instant.ofEpochMilli(startedAtMs),
        tableNames.size(),
        tableNames);
    return state;
  }

  private List<String> discoverTables() throws Exception {
    String coordinatorTable =
        ScalarDbUtils.getFullTableName(Coordinator.NAMESPACE, Coordinator.TABLE);
    List<String> tables = new ArrayList<>();
    for (String namespace : admin.getNamespaceNames()) {
      for (String table : admin.getNamespaceTableNames(namespace)) {
        String qualifiedTable = ScalarDbUtils.getFullTableName(namespace, table);
        if (!qualifiedTable.equals(coordinatorTable)) {
          tables.add(qualifiedTable);
        }
      }
    }
    return tables;
  }

  /**
   * Scans a single table using a resumable scanner, finalizes non-terminal records via the {@link
   * RecordFinalizer} for recovery, and marks the table as completed in the checkpoint state.
   */
  private void processTable(
      LedgerFinalizeStateManager stateManager,
      LedgerFinalizeState state,
      String qualifiedTable,
      RecordStateChecker stateChecker)
      throws Exception {
    Path scanCheckpointDir = stateManager.getStateDir();
    String[] parts = qualifiedTable.split("\\.", 2);
    String namespace = parts[0];
    String tableName = parts[1];

    logger.info("Starting to process the table: {}", qualifiedTable);

    FinalizeTransactionRecordHandler handler =
        new FinalizeTransactionRecordHandler(
            stateChecker, new RecordFinalizer(txManager), namespace, tableName);

    try (ResumableScanner scanner = scannerFactory.create(scanCheckpointDir)) {
      ScanResult scanResult = scanner.scan(namespace, tableName, handler);

      logger.info(
          "Finished processing the table: {}. {} records were scanned, {} records were finalized.",
          qualifiedTable,
          scanResult.getTotalScanned(),
          handler.getFinalizedCount());
    }

    state.markTableCompleted(qualifiedTable);
    stateManager.persist(state);
  }

  /**
   * Releases the resources held by this orchestrator. Any failure during close is logged and
   * suppressed rather than propagated.
   */
  @Override
  public void close() {
    try {
      admin.close();
    } catch (Exception e) {
      logger.warn("Failed to close DistributedStorageAdmin.", e);
    }
    try {
      txManager.close();
    } catch (Exception e) {
      logger.warn("Failed to close DistributedTransactionManager.", e);
    }
  }
}
