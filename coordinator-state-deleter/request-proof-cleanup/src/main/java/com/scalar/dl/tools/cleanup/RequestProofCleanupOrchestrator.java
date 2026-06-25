package com.scalar.dl.tools.cleanup;

import com.google.common.annotations.VisibleForTesting;
import com.scalar.db.api.DistributedStorage;
import com.scalar.db.config.DatabaseConfig;
import com.scalar.db.service.StorageFactory;
import com.scalar.dl.tools.common.AuditorInternalValues;
import com.scalar.dl.tools.common.CompletionToken;
import com.scalar.dl.tools.scan.ResumableScanner;
import com.scalar.dl.tools.scan.ResumableScannerFactory;
import com.scalar.dl.tools.scan.ScanResult;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Properties;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrates the {@code request-proof-cleanup} workflow.
 *
 * <p>This class receives the auditor completion token (from {@code auditor-finalize-records}), uses
 * its guarantee timestamp as the deletable-before boundary, scans the Auditor's single global
 * {@code request_proof} table, and deletes every record whose {@code registered_at} is before that
 * boundary.
 *
 * <p>The workflow is resumable: progress is checkpointed so that a failure only requires
 * re-invocation with the same checkpoint directory.
 */
public final class RequestProofCleanupOrchestrator implements AutoCloseable {

  private static final Logger logger =
      LoggerFactory.getLogger(RequestProofCleanupOrchestrator.class);

  private final DistributedStorage storage;
  private final ResumableScannerFactory scannerFactory;
  private final Path checkpointDir;
  private final String baseNamespace;
  @Nullable private final String auditorTokenString;
  private final RequestProofDeleter recordDeleter;

  @VisibleForTesting
  RequestProofCleanupOrchestrator(
      DistributedStorage storage,
      ResumableScannerFactory scannerFactory,
      Path checkpointDir,
      String baseNamespace,
      @Nullable String auditorTokenString) {
    this(
        storage,
        scannerFactory,
        checkpointDir,
        baseNamespace,
        auditorTokenString,
        new RequestProofDeleter(storage, baseNamespace));
  }

  @VisibleForTesting
  RequestProofCleanupOrchestrator(
      DistributedStorage storage,
      ResumableScannerFactory scannerFactory,
      Path checkpointDir,
      String baseNamespace,
      @Nullable String auditorTokenString,
      RequestProofDeleter recordDeleter) {
    this.storage = storage;
    this.scannerFactory = scannerFactory;
    this.checkpointDir = checkpointDir;
    this.baseNamespace = baseNamespace;
    this.auditorTokenString = auditorTokenString;
    this.recordDeleter = recordDeleter;
  }

  /**
   * Creates an orchestrator.
   *
   * @param props the properties used by the ScalarDL Auditor
   * @param checkpointDir root directory for checkpoint state
   * @param auditorTokenString the auditor completion token
   * @return a new orchestrator instance
   */
  public static RequestProofCleanupOrchestrator create(
      Properties props, Path checkpointDir, @Nullable String auditorTokenString) {
    DatabaseConfig dbConfig = new DatabaseConfig(props);
    DistributedStorage storage = StorageFactory.create(props).getStorage();
    try {
      ResumableScannerFactory scannerFactory = new ResumableScannerFactory(dbConfig);
      return new RequestProofCleanupOrchestrator(
          storage, scannerFactory, checkpointDir, resolveBaseNamespace(props), auditorTokenString);
    } catch (Exception e) {
      storage.close();
      throw e;
    }
  }

  /**
   * Resolves the namespace of the {@code request_proof} table. It lives in the Auditor base
   * namespace ({@code scalar.dl.auditor.namespace}, default {@code auditor}); the table is global,
   * not per logical namespace.
   */
  private static String resolveBaseNamespace(Properties props) {
    return props.getProperty(
        AuditorInternalValues.AUDITOR_NAMESPACE_PROPERTY,
        AuditorInternalValues.DEFAULT_BASE_NAMESPACE);
  }

  /**
   * Executes the full cleanup workflow: load or initialize state, scan the {@code request_proof}
   * table, and delete deletable records. On the first run, the auditor completion token is parsed
   * to determine the deletable-before timestamp. On subsequent runs, the timestamp is restored from
   * the checkpoint.
   *
   * @throws Exception if token validation, scanning, or state persistence fails
   */
  public void execute() throws Exception {
    RequestProofCleanupStateManager stateManager =
        new RequestProofCleanupStateManager(checkpointDir);
    RequestProofCleanupState state = loadOrInitializeState(stateManager);

    if (state.isCompleted()) {
      logger.info("The cleanup has already been completed for this checkpoint; nothing to do.");
      return;
    }

    scanAndDelete(stateManager, state.getDeletableBeforeMs());

    state.markCompleted();
    stateManager.persist(state);

    logger.info("Cleanup completed successfully.");
  }

  /**
   * Loads persisted state if available; otherwise parses the token, computes the safe-deletion
   * boundary, and persists the initial state.
   */
  private RequestProofCleanupState loadOrInitializeState(
      RequestProofCleanupStateManager stateManager) {
    RequestProofCleanupState state = stateManager.load();

    if (state != null) {
      // The tool is resumable and idempotent: re-invoking the same command (including the
      // completion token) with an existing checkpoint simply resumes the previous run. The
      // specified token is ignored so that the deletion boundary stays fixed across retries.
      if (auditorTokenString != null) {
        logger.warn("A checkpoint already exists; the specified completion token is ignored.");
      }

      if (state.isCompleted()) {
        logger.info("Found existing checkpoint data; the cleanup has already been completed.");
      } else {
        logger.info(
            "Found existing checkpoint data; resuming the previous run. "
                + "Records registered before {} will be deleted.",
            Instant.ofEpochMilli(state.getDeletableBeforeMs()));
      }

      return state;
    }

    if (auditorTokenString == null) {
      throw new IllegalArgumentException(
          "The auditor completion token is required for the initial run");
    }

    long deletableBeforeMs = parseDeletableBeforeMs();
    state = new RequestProofCleanupState(deletableBeforeMs);
    stateManager.persist(state);
    logger.info(
        "Starting a new run. Records registered before {} will be deleted.",
        Instant.ofEpochMilli(deletableBeforeMs));

    return state;
  }

  /** Parses and validates the auditor token, then returns its guarantee timestamp. */
  private long parseDeletableBeforeMs() {
    CompletionToken auditorToken = CompletionToken.decode(auditorTokenString);
    if (auditorToken.getServerType() != CompletionToken.ServerType.AUDITOR) {
      throw new IllegalArgumentException(
          "Auditor token has wrong server type: " + auditorToken.getServerType());
    }

    long guaranteeTimestamp = auditorToken.getStartedAtMs();
    logger.info(
        "Completion token parsed: auditor guarantee timestamp is {}",
        Instant.ofEpochMilli(guaranteeTimestamp));

    return guaranteeTimestamp;
  }

  /** Scans the {@code request_proof} table and deletes deletable records. */
  private void scanAndDelete(RequestProofCleanupStateManager stateManager, long deletableBeforeMs)
      throws Exception {
    Path scanCheckpointDir = stateManager.getStateDir();

    logger.info("Starting to scan the request_proof table");

    DeleteRequestProofHandler handler =
        new DeleteRequestProofHandler(
            new RequestProofDeletionChecker(deletableBeforeMs), recordDeleter);

    try (ResumableScanner scanner = scannerFactory.create(scanCheckpointDir)) {
      ScanResult scanResult =
          scanner.scan(baseNamespace, AuditorInternalValues.REQUEST_PROOF_TABLE_NAME, handler);

      // The deletion count is per run (command execution) only. Tracking a cumulative total across
      // runs would require persisting it on every deletion, so we report only what this run did.
      logger.info("Finished scanning the request_proof table.");
      logger.info("Scanned records in this run: {}", scanResult.getTotalScanned());
      logger.info("Deleted records in this run: {}", handler.getDeletedCount());
    }
  }

  /**
   * Releases the resources held by this orchestrator. Any failure during close is logged and
   * suppressed rather than propagated.
   */
  @Override
  public void close() {
    try {
      storage.close();
    } catch (Exception e) {
      logger.warn("Failed to close DistributedStorage.", e);
    }
  }
}
