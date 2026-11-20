package com.scalar.dl.tools.cleanup;

import com.google.common.annotations.VisibleForTesting;
import com.scalar.db.api.DistributedStorageAdmin;
import com.scalar.db.api.DistributedTransactionManager;
import com.scalar.db.config.DatabaseConfig;
import com.scalar.db.service.StorageFactory;
import com.scalar.db.service.TransactionFactory;
import com.scalar.db.storage.multistorage.MultiStorageAdmin;
import com.scalar.db.storage.multistorage.MultiStorageConfig;
import com.scalar.dl.tools.common.CompletionToken;
import com.scalar.dl.tools.common.LedgerConfigValidator;
import com.scalar.dl.tools.common.ScalarDlCleanupError;
import com.scalar.dl.tools.common.ScalarDlCleanupException;
import com.scalar.dl.tools.common.StorageValidator;
import com.scalar.dl.tools.scan.ResumableScanner;
import com.scalar.dl.tools.scan.ResumableScannerFactory;
import com.scalar.dl.tools.scan.ScanResult;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import javax.annotation.Nullable;
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
 * are captured once on the first invocation and reused across resumptions. The fingerprint of the
 * storage configuration is captured together with them, and a resumption with another storage
 * configuration fails, because the target table set would not match that configuration.
 */
public final class LedgerFinalizeOrchestrator implements AutoCloseable {

  private static final Logger logger = LoggerFactory.getLogger(LedgerFinalizeOrchestrator.class);

  private final DistributedStorageAdmin admin;
  private final DistributedTransactionManager txManager;
  private final ResumableScannerFactory scannerFactory;
  private final Path checkpointDir;
  private final TransactionalTableDiscovery tableDiscovery;

  /** The validator of the namespace mapping. Null unless the storage is multi-storage. */
  @Nullable private final NamespaceMappingValidator namespaceMappingValidator;

  /** The fingerprint of the current storage configuration. */
  private final Map<String, String> storageConfig;

  @VisibleForTesting
  LedgerFinalizeOrchestrator(
      DistributedStorageAdmin admin,
      DistributedTransactionManager txManager,
      ResumableScannerFactory scannerFactory,
      Path checkpointDir,
      String coordinatorNamespace,
      @Nullable NamespaceMappingValidator namespaceMappingValidator,
      Map<String, String> storageConfig) {
    this.admin = admin;
    this.txManager = txManager;
    this.scannerFactory = scannerFactory;
    this.checkpointDir = checkpointDir;
    this.tableDiscovery = new TransactionalTableDiscovery(admin, coordinatorNamespace);
    this.namespaceMappingValidator = namespaceMappingValidator;
    this.storageConfig = storageConfig;
  }

  /**
   * Creates an orchestrator.
   *
   * @param props the properties used by the ScalarDL Ledger
   * @param checkpointDir root directory for checkpoint state
   * @return a new orchestrator instance
   * @throws ScalarDlCleanupException if the configuration is not supported
   */
  public static LedgerFinalizeOrchestrator create(Properties props, Path checkpointDir) {
    DistributedStorageAdmin admin = null;
    DistributedTransactionManager txManager = null;
    try {
      DatabaseConfig databaseConfig = new DatabaseConfig(props);
      StorageValidator.validateAllowingMultiStorage(databaseConfig);
      LedgerConfigValidator.validate(databaseConfig);
      StorageFactory storageFactory = StorageFactory.create(props);
      admin = storageFactory.getStorageAdmin();
      txManager = TransactionFactory.create(props).getTransactionManager();
      ResumableScannerFactory scannerFactory = new ResumableScannerFactory(databaseConfig);
      return new LedgerFinalizeOrchestrator(
          admin,
          txManager,
          scannerFactory,
          checkpointDir,
          TransactionalTableDiscovery.resolveCoordinatorNamespace(databaseConfig),
          createNamespaceMappingValidator(admin, databaseConfig),
          StorageConfigFingerprint.create(databaseConfig));
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
   * Loads persisted state if available, after checking that it was created with the current storage
   * configuration; otherwise captures the start timestamp, discovers all transactional tables via
   * the Admin API, and persists the initial state.
   */
  private LedgerFinalizeState loadOrInitializeState(LedgerFinalizeStateManager stateManager)
      throws Exception {
    LedgerFinalizeState state = stateManager.load();
    if (state != null) {
      // The check applies to a completed run too, so that its token is not emitted again for a
      // configuration that might find other tables.
      throwIfStorageConfigChanged(state);
      logger.info(
          "Found existing checkpoint data; resuming the previous run started at {}. "
              + "{} of {} target tables have already been processed.",
          Instant.ofEpochMilli(state.getStartedAtMs()),
          state.getCompletedTables().size(),
          state.getTableList().size());
      return state;
    }

    long startedAtMs = System.currentTimeMillis();
    // This run fixes the target tables, so a wrong namespace mapping must be caught here.
    if (namespaceMappingValidator != null) {
      namespaceMappingValidator.validate();
    }
    List<String> tableNames = tableDiscovery.discover();
    state = new LedgerFinalizeState(startedAtMs, tableNames, storageConfig);
    stateManager.persist(state);
    logger.info(
        "Starting a new run at {}. {} target tables were found: {}",
        Instant.ofEpochMilli(startedAtMs),
        tableNames.size(),
        tableNames);
    return state;
  }

  /**
   * Throws if the persisted state was created with another storage configuration than the current
   * one, or by an older version of the tool that did not keep the storage configuration.
   */
  private void throwIfStorageConfigChanged(LedgerFinalizeState state) {
    Map<String, String> savedStorageConfig = state.getStorageConfig();
    if (savedStorageConfig == null) {
      throw new ScalarDlCleanupException(
          ScalarDlCleanupError.STORAGE_CONFIGURATION_NOT_IN_CHECKPOINT);
    }
    if (!savedStorageConfig.equals(storageConfig)) {
      throw new ScalarDlCleanupException(
          ScalarDlCleanupError.STORAGE_CONFIGURATION_CHANGED,
          StorageConfigFingerprint.describeDifferences(savedStorageConfig, storageConfig));
    }
  }

  /**
   * Returns the validator of the namespace mapping, or null if the storage is not multi-storage.
   *
   * @throws IllegalStateException if the admin of multi-storage is not a {@link MultiStorageAdmin}
   */
  @VisibleForTesting
  @Nullable
  static NamespaceMappingValidator createNamespaceMappingValidator(
      DistributedStorageAdmin admin, DatabaseConfig databaseConfig) {
    if (!MultiStorageConfig.STORAGE_NAME.equals(databaseConfig.getStorage())) {
      return null;
    }
    // The validation must not be skipped silently, so an unexpected admin is an error.
    if (!(admin instanceof MultiStorageAdmin multiStorageAdmin)) {
      throw new IllegalStateException(
          "Unexpected admin for multi-storage: " + admin.getClass().getName());
    }
    return NamespaceMappingValidator.create(
        multiStorageAdmin, new MultiStorageConfig(databaseConfig));
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

    try (ResumableScanner scanner = scannerFactory.create(namespace, scanCheckpointDir)) {
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
