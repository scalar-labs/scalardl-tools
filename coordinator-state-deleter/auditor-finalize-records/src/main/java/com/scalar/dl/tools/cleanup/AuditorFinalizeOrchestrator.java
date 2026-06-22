package com.scalar.dl.tools.cleanup;

import com.google.common.annotations.VisibleForTesting;
import com.scalar.db.api.DistributedStorage;
import com.scalar.db.api.DistributedStorageAdmin;
import com.scalar.db.api.Result;
import com.scalar.db.api.Scan;
import com.scalar.db.api.Scanner;
import com.scalar.db.config.DatabaseConfig;
import com.scalar.db.io.Key;
import com.scalar.db.service.StorageFactory;
import com.scalar.dl.client.config.ClientConfig;
import com.scalar.dl.client.service.AuditorClient;
import com.scalar.dl.tools.common.CompletionToken;
import com.scalar.dl.tools.scan.ResumableScanner;
import com.scalar.dl.tools.scan.ResumableScannerFactory;
import com.scalar.dl.tools.scan.ScanResult;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
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
  }

  /**
   * Creates an orchestrator.
   *
   * @param auditorProps the properties used by the ScalarDL Auditor
   * @param clientProps the properties used by the ScalarDL Client
   * @param checkpointDir root directory for checkpoint state
   * @return a new orchestrator instance
   * @throws IOException if the client configuration cannot be loaded
   */
  // TODO: unify auditorProps and clientProps into a single Properties so callers only supply one
  //  configuration. We plan to make AuditorClient buildable without requiring additional client
  //  configuration as much as possible.
  public static AuditorFinalizeOrchestrator create(
      Properties auditorProps, Properties clientProps, Path checkpointDir) throws IOException {
    DistributedStorageAdmin admin = null;
    DistributedStorage storage = null;
    AuditorClient auditorClient = null;
    try {
      DatabaseConfig databaseConfig = new DatabaseConfig(auditorProps);
      StorageFactory storageFactory = StorageFactory.create(auditorProps);
      admin = storageFactory.getStorageAdmin();
      storage = storageFactory.getStorage();

      ClientConfig clientConfig = new ClientConfig(clientProps);
      if (clientConfig.getAuditorTargetConfig() == null) {
        throw new IllegalArgumentException("Auditor target configuration is missing.");
      }
      auditorClient = new AuditorClient(clientConfig.getAuditorTargetConfig());

      String baseNamespace =
          auditorProps.getProperty(
              AuditorInternalValues.AUDITOR_NAMESPACE_PROPERTY,
              AuditorInternalValues.DEFAULT_BASE_NAMESPACE);

      ResumableScannerFactory scannerFactory = new ResumableScannerFactory(databaseConfig);
      return new AuditorFinalizeOrchestrator(
          admin, storage, auditorClient, scannerFactory, checkpointDir, baseNamespace);
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
    List<String> namespaces = discoverLogicalNamespaces();
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
   * Discovers the logical namespaces to sweep: the implicit {@code default} namespace plus every
   * namespace registered in the {@code <base>.namespace} registry table, or only the default
   * namespace if that table does not exist.
   */
  private List<String> discoverLogicalNamespaces() throws Exception {
    Set<String> namespaces = new LinkedHashSet<>();
    namespaces.add(AuditorInternalValues.DEFAULT_LOGICAL_NAMESPACE);

    if (admin.tableExists(baseNamespace, AuditorInternalValues.NAMESPACE_TABLE)) {
      logger.info("Namespace registry table found; scanning for registered namespaces.");
      Scan scan =
          Scan.newBuilder()
              .namespace(baseNamespace)
              .table(AuditorInternalValues.NAMESPACE_TABLE)
              .partitionKey(
                  Key.ofInt(
                      AuditorInternalValues.NAMESPACE_COLUMN_PARTITION_ID,
                      AuditorInternalValues.NAMESPACE_DEFAULT_PARTITION_ID))
              .build();
      try (Scanner scanner = storage.scan(scan)) {
        for (Result result : scanner) {
          String registered = result.getText(AuditorInternalValues.NAMESPACE_COLUMN_NAME);
          if (namespaces.add(registered)) {
            logger.info("Discovered registered namespace: {}", registered);
          }
        }
      }
    } else {
      logger.info("Namespace registry table not found; sweeping the default namespace only.");
    }

    return new ArrayList<>(namespaces);
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

    try (ResumableScanner scanner = scannerFactory.create(scanCheckpointDir)) {
      ScanResult scanResult =
          scanner.scan(physicalNamespace, AuditorInternalValues.TABLE_NAME, handler);

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
