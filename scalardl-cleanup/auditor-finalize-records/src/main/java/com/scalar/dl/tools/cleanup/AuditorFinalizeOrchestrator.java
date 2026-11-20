package com.scalar.dl.tools.cleanup;

import com.google.common.annotations.VisibleForTesting;
import com.scalar.db.api.DistributedStorage;
import com.scalar.db.api.DistributedStorageAdmin;
import com.scalar.db.config.DatabaseConfig;
import com.scalar.db.service.StorageFactory;
import com.scalar.dl.client.service.AuditorClient;
import com.scalar.dl.tools.common.AuditorInternalValues;
import com.scalar.dl.tools.common.CompletionToken;
import com.scalar.dl.tools.common.ScalarDlCleanupException;
import com.scalar.dl.tools.common.StorageValidator;
import com.scalar.dl.tools.scan.ResumableScanner;
import com.scalar.dl.tools.scan.ResumableScannerFactory;
import com.scalar.dl.tools.scan.ScanResult;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrates the {@code auditor-finalize-records} workflow.
 *
 * <p>This class discovers all {@code asset_lock} tables, scans each table using a resumable
 * scanner, and finalizes every unreleased lock. On completion, it emits a {@link CompletionToken}
 * that is later consumed by {@code coordinator-state-cleanup}.
 *
 * <p>The workflow is resumable: progress is checkpointed per namespace, so a failure only requires
 * re-invocation with the same checkpoint directory. The start timestamp and the target namespace
 * set are captured once on the first invocation and reused across resumptions.
 */
public final class AuditorFinalizeOrchestrator implements AutoCloseable {

  private static final Logger logger = LoggerFactory.getLogger(AuditorFinalizeOrchestrator.class);

  private final DistributedStorageAdmin admin;
  private final DistributedStorage storage;
  private final AuditorClient auditorClient;
  private final ResumableScannerFactory scannerFactory;
  private final Path checkpointDir;
  private final String baseNamespace;
  private final AuditorNamespaceDiscovery namespaceDiscovery;

  @VisibleForTesting
  AuditorFinalizeOrchestrator(
      DistributedStorageAdmin admin,
      DistributedStorage storage,
      AuditorClient auditorClient,
      ResumableScannerFactory scannerFactory,
      Path checkpointDir,
      String baseNamespace) {
    this.admin = admin;
    this.storage = storage;
    this.auditorClient = auditorClient;
    this.scannerFactory = scannerFactory;
    this.checkpointDir = checkpointDir;
    this.baseNamespace = baseNamespace;
    this.namespaceDiscovery = new AuditorNamespaceDiscovery(admin, storage, baseNamespace);
  }

  /**
   * Creates an orchestrator.
   *
   * @param props the settings for connecting to the Auditor server, in addition to the properties
   *     used by the ScalarDL Auditor
   * @param checkpointDir root directory for checkpoint state
   * @return a new orchestrator instance
   * @throws ScalarDlCleanupException if the configuration is not supported
   */
  public static AuditorFinalizeOrchestrator create(Properties props, Path checkpointDir) {
    DistributedStorageAdmin admin = null;
    DistributedStorage storage = null;
    AuditorClient auditorClient = null;
    try {
      DatabaseConfig databaseConfig = new DatabaseConfig(props);
      StorageValidator.validate(databaseConfig);
      StorageFactory storageFactory = StorageFactory.create(props);
      admin = storageFactory.getStorageAdmin();
      storage = storageFactory.getStorage();

      AuditorFinalizeConfig config = new AuditorFinalizeConfig(props);
      auditorClient = new AuditorClient(config.getAuditorTargetConfig());

      ResumableScannerFactory scannerFactory = new ResumableScannerFactory(databaseConfig);
      return new AuditorFinalizeOrchestrator(
          admin, storage, auditorClient, scannerFactory, checkpointDir, config.getBaseNamespace());
    } catch (Exception e) {
      if (auditorClient != null) {
        auditorClient.shutdown();
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
   * Executes the full orchestration workflow: load or initialize state, sweep each logical
   * namespace's {@code asset_lock} table finalizing unreleased locks, and emit a completion token.
   *
   * @return the base64url-encoded completion token
   * @throws Exception if any processing or state persistence fails
   */
  public String execute() throws Exception {
    AuditorFinalizeStateManager stateManager = new AuditorFinalizeStateManager(checkpointDir);
    AuditorFinalizeState state = loadOrInitializeState(stateManager);

    long startedAtMs = state.getStartedAtMs();
    LockStateChecker stateChecker = new LockStateChecker(startedAtMs);

    for (String logicalNamespace : state.getNamespaceList()) {
      if (state.getCompletedNamespaces().contains(logicalNamespace)) {
        logger.info(
            "Skipping already finalized asset_lock table in namespace: {}", logicalNamespace);
        continue;
      }
      processAssetLockTable(stateManager, state, logicalNamespace, stateChecker);
    }

    String completionToken =
        CompletionToken.create(CompletionToken.ServerType.AUDITOR, startedAtMs).encode();
    logger.info("Completion token generated successfully: {}", completionToken);
    return completionToken;
  }

  /**
   * Loads persisted state if available; otherwise captures the start timestamp ({@code now() -
   * 15s}), discovers the logical namespaces to sweep, and persists the initial state. The namespace
   * set is captured exactly once here so that a resumed run scans the same set.
   */
  private AuditorFinalizeState loadOrInitializeState(AuditorFinalizeStateManager stateManager)
      throws Exception {
    AuditorFinalizeState state = stateManager.load();
    if (state != null) {
      logger.info(
          "Found existing checkpoint data; resuming the previous run started at {}. "
              + "{} of {} asset_lock tables have already been finalized.",
          Instant.ofEpochMilli(state.getStartedAtMs()),
          state.getCompletedNamespaces().size(),
          state.getNamespaceList().size());
      return state;
    }

    long startedAtMs = System.currentTimeMillis() - AuditorInternalValues.LOCK_VALID_PERIOD_MS;
    List<String> namespaces = namespaceDiscovery.discoverLogicalNamespaces();
    state = new AuditorFinalizeState(startedAtMs, namespaces);
    stateManager.persist(state);
    logger.info(
        "Starting a new run at {}. asset_lock tables in {} namespaces will be swept: {}",
        Instant.ofEpochMilli(startedAtMs),
        namespaces.size(),
        namespaces);
    return state;
  }

  /**
   * Scans a single namespace's {@code asset_lock} table using a resumable scanner, finalizes
   * unreleased locks via the {@link LockFinalizer}, and marks the namespace as completed in the
   * checkpoint state.
   */
  private void processAssetLockTable(
      AuditorFinalizeStateManager stateManager,
      AuditorFinalizeState state,
      String logicalNamespace,
      LockStateChecker stateChecker)
      throws Exception {
    String physicalNamespace =
        AuditorInternalValues.resolveNamespace(baseNamespace, logicalNamespace);
    Path scanCheckpointDir = stateManager.getStateDir();

    logger.info("Processing asset_lock table in namespace: {}", logicalNamespace);

    LockFinalizer lockFinalizer = new LockFinalizer(auditorClient);
    FinalizeAssetLockHandler handler =
        new FinalizeAssetLockHandler(stateChecker, lockFinalizer, logicalNamespace);

    try (ResumableScanner scanner = scannerFactory.create(physicalNamespace, scanCheckpointDir)) {
      ScanResult scanResult =
          scanner.scan(physicalNamespace, AuditorInternalValues.ASSET_LOCK_TABLE_NAME, handler);

      logger.info(
          "Finished asset_lock table in namespace {}: {} records were scanned, {} locks were"
              + " finalized.",
          logicalNamespace,
          scanResult.getTotalScanned(),
          handler.getFinalizedCount());
    }

    state.markNamespaceCompleted(logicalNamespace);
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
      storage.close();
    } catch (Exception e) {
      logger.warn("Failed to close DistributedStorage.", e);
    }
    try {
      auditorClient.shutdown();
    } catch (Exception e) {
      logger.warn("Failed to close AuditorClient.", e);
    }
  }
}
