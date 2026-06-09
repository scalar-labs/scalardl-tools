package com.scalar.dl.tools.cleanup;

import com.google.common.annotations.VisibleForTesting;
import com.scalar.db.api.DistributedStorage;
import com.scalar.db.api.DistributedStorageAdmin;
import com.scalar.db.api.DistributedTransactionManager;
import com.scalar.db.config.DatabaseConfig;
import com.scalar.db.service.StorageFactory;
import com.scalar.db.service.TransactionFactory;
import com.scalar.db.transaction.consensuscommit.Coordinator;
import com.scalar.db.util.ScalarDbUtils;
import com.scalar.dl.tools.common.CompletionToken;
import com.scalar.dl.tools.scan.ResumableScanner;
import com.scalar.dl.tools.scan.ResumableScannerFactory;
import com.scalar.dl.tools.scan.ScanResult;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.Consumer;
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
 * re-invocation with the same checkpoint directory. The guarantee timestamp is captured once on the
 * first invocation and reused across resumptions.
 */
public final class LedgerFinalizeOrchestrator implements AutoCloseable {

  private static final Logger logger = LoggerFactory.getLogger(LedgerFinalizeOrchestrator.class);

  @VisibleForTesting
  @FunctionalInterface
  interface RecordFinalizerFactory {
    RecordFinalizer create(
        DistributedTransactionManager txManager,
        DistributedStorage storage,
        RecordStateChecker stateChecker);
  }

  private final DistributedStorageAdmin admin;
  private final DistributedStorage storage;
  private final DistributedTransactionManager txManager;
  private final ResumableScannerFactory scannerFactory;
  private final Path checkpointDir;
  private final RecordFinalizerFactory recordFinalizerFactory;

  @VisibleForTesting
  LedgerFinalizeOrchestrator(
      DistributedStorageAdmin admin,
      DistributedStorage storage,
      DistributedTransactionManager txManager,
      ResumableScannerFactory scannerFactory,
      Path checkpointDir) {
    this(admin, storage, txManager, scannerFactory, checkpointDir, RecordFinalizer::new);
  }

  @VisibleForTesting
  LedgerFinalizeOrchestrator(
      DistributedStorageAdmin admin,
      DistributedStorage storage,
      DistributedTransactionManager txManager,
      ResumableScannerFactory scannerFactory,
      Path checkpointDir,
      RecordFinalizerFactory recordFinalizerFactory) {
    this.admin = admin;
    this.storage = storage;
    this.txManager = txManager;
    this.scannerFactory = scannerFactory;
    this.checkpointDir = checkpointDir;
    this.recordFinalizerFactory = recordFinalizerFactory;
  }

  /**
   * Creates an orchestrator from raw ScalarDB properties.
   *
   * @param props properties containing ScalarDB database configuration information
   * @param checkpointDir root directory for checkpoint state
   * @return a new orchestrator instance
   * @throws IllegalStateException if the configured storage is not supported
   */
  public static LedgerFinalizeOrchestrator create(Properties props, Path checkpointDir) {
    DistributedStorageAdmin admin = null;
    DistributedStorage storage = null;
    DistributedTransactionManager txManager = null;
    try {
      StorageFactory storageFactory = StorageFactory.create(props);
      admin = storageFactory.getStorageAdmin();
      storage = storageFactory.getStorage();
      txManager = TransactionFactory.create(props).getTransactionManager();
      ResumableScannerFactory scannerFactory =
          new ResumableScannerFactory(new DatabaseConfig(props));
      return new LedgerFinalizeOrchestrator(
          admin, storage, txManager, scannerFactory, checkpointDir);
    } catch (Exception e) {
      if (txManager != null) {
        txManager.close();
      }
      if (storage != null) {
        storage.close();
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

    long guaranteeTimestamp = state.getStartedAtMs();
    RecordStateChecker stateChecker = new RecordStateChecker(guaranteeTimestamp);

    for (String qualifiedTable : state.getTableList()) {
      if (state.getCompletedTables().contains(qualifiedTable)) {
        logger.info("Skipping already completed table: {}", qualifiedTable);
        continue;
      }
      processTable(stateManager, state, qualifiedTable, stateChecker);
    }

    String completionToken =
        CompletionToken.create(CompletionToken.ServerType.LEDGER, guaranteeTimestamp).encode();
    logger.info("Completion token emitted successfully");
    return completionToken;
  }

  /**
   * Loads persisted state if available; otherwise captures {@code t_L}, discovers all transactional
   * tables via the Admin API, and persists the initial state.
   */
  private LedgerFinalizeState loadOrInitializeState(LedgerFinalizeStateManager stateManager)
      throws Exception {
    LedgerFinalizeState state = stateManager.load();
    if (state != null) {
      logger.info(
          "Resumed state: t_L={}, completed={}/{}",
          state.getStartedAtMs(),
          state.getCompletedTables().size(),
          state.getTableList().size());
      return state;
    }

    long tL = System.currentTimeMillis();
    List<String> tableNames = discoverTables();
    state = new LedgerFinalizeState(tL, tableNames, new ArrayList<>());
    stateManager.persist(state);
    logger.info("Initialized state: t_L={}, tables={}", tL, tableNames);
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

    logger.info("Processing table: {}", qualifiedTable);

    RecordFinalizer recordFinalizer =
        recordFinalizerFactory.create(txManager, storage, stateChecker);

    try (ResumableScanner scanner = scannerFactory.create(scanCheckpointDir)) {
      Consumer<com.scalar.db.api.Result> consumer =
          result -> {
            if (stateChecker.needsFinalization(result)) {
              try {
                recordFinalizer.execute(namespace, tableName, result);
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            }
          };

      ScanResult scanResult = scanner.scan(namespace, tableName, consumer);

      logger.info(
          "Table {} complete: scanned={}, finalized={}",
          qualifiedTable,
          scanResult.getTotalScanned(),
          recordFinalizer.getFinalizedCount());
    }

    state.markTableCompleted(qualifiedTable);
    stateManager.persist(state);
  }

  @Override
  public void close() {
    try {
      admin.close();
    } catch (Exception e) {
      logger.warn("Failed to close DistributedStorageAdmin.", e);
    }
    try {
      storage.close();
    } catch (Exception e) {
      logger.warn("Failed to close DistributedStorage.", e);
    }
    try {
      txManager.close();
    } catch (Exception e) {
      logger.warn("Failed to close DistributedTransactionManager.", e);
    }
  }
}
