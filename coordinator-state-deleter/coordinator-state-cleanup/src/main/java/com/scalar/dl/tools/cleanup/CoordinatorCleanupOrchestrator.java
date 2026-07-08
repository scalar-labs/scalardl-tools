package com.scalar.dl.tools.cleanup;

import com.google.common.annotations.VisibleForTesting;
import com.scalar.db.api.DistributedStorage;
import com.scalar.db.config.DatabaseConfig;
import com.scalar.db.service.StorageFactory;
import com.scalar.db.transaction.consensuscommit.ConsensusCommitConfig;
import com.scalar.db.transaction.consensuscommit.Coordinator;
import com.scalar.dl.tools.common.CompletionToken;
import com.scalar.dl.tools.common.CoordinatorStateDeleterError;
import com.scalar.dl.tools.common.CoordinatorStateDeleterException;
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
 * Orchestrates the {@code coordinator-state-cleanup} workflow.
 *
 * <p>This class receives two completion tokens (from {@code ledger-finalize-records} and {@code
 * auditor-finalize-records}), derives the deletable-before timestamp as the earlier of the two
 * timestamps from the tokens, scans the coordinator table, and deletes every record whose {@code
 * tx_created_at} is before that boundary.
 *
 * <p>The workflow is resumable: progress is checkpointed so that a failure only requires
 * re-invocation with the same checkpoint directory.
 */
public final class CoordinatorCleanupOrchestrator implements AutoCloseable {

  private static final Logger logger =
      LoggerFactory.getLogger(CoordinatorCleanupOrchestrator.class);

  private final DistributedStorage storage;
  private final ResumableScannerFactory scannerFactory;
  private final Path checkpointDir;
  private final String coordinatorNamespace;
  @Nullable private final String ledgerTokenString;
  @Nullable private final String auditorTokenString;
  private final RecordDeleter recordDeleter;

  @VisibleForTesting
  CoordinatorCleanupOrchestrator(
      DistributedStorage storage,
      ResumableScannerFactory scannerFactory,
      Path checkpointDir,
      String coordinatorNamespace,
      @Nullable String ledgerTokenString,
      @Nullable String auditorTokenString) {
    this(
        storage,
        scannerFactory,
        checkpointDir,
        coordinatorNamespace,
        ledgerTokenString,
        auditorTokenString,
        new RecordDeleter(storage, coordinatorNamespace));
  }

  @VisibleForTesting
  CoordinatorCleanupOrchestrator(
      DistributedStorage storage,
      ResumableScannerFactory scannerFactory,
      Path checkpointDir,
      String coordinatorNamespace,
      @Nullable String ledgerTokenString,
      @Nullable String auditorTokenString,
      RecordDeleter recordDeleter) {
    this.storage = storage;
    this.scannerFactory = scannerFactory;
    this.checkpointDir = checkpointDir;
    this.coordinatorNamespace = coordinatorNamespace;
    this.ledgerTokenString = ledgerTokenString;
    this.auditorTokenString = auditorTokenString;
    this.recordDeleter = recordDeleter;
  }

  /**
   * Creates an orchestrator.
   *
   * @param props the properties used by the ScalarDL Ledger
   * @param checkpointDir root directory for checkpoint state
   * @param ledgerTokenString the Ledger completion token
   * @param auditorTokenString the Auditor completion token
   * @return a new orchestrator instance
   */
  public static CoordinatorCleanupOrchestrator create(
      Properties props,
      Path checkpointDir,
      @Nullable String ledgerTokenString,
      @Nullable String auditorTokenString) {
    DatabaseConfig dbConfig = new DatabaseConfig(props);
    DistributedStorage storage = StorageFactory.create(props).getStorage();
    try {
      ResumableScannerFactory scannerFactory = new ResumableScannerFactory(dbConfig);
      return new CoordinatorCleanupOrchestrator(
          storage,
          scannerFactory,
          checkpointDir,
          resolveCoordinatorNamespace(dbConfig),
          ledgerTokenString,
          auditorTokenString);
    } catch (Exception e) {
      storage.close();
      throw e;
    }
  }

  /** Resolves the namespace of the coordinator table. */
  private static String resolveCoordinatorNamespace(DatabaseConfig dbConfig) {
    return new ConsensusCommitConfig(dbConfig)
        .getCoordinatorNamespace()
        .orElse(Coordinator.NAMESPACE);
  }

  /**
   * Executes the full cleanup workflow: load or initialize state, scan the coordinator table, and
   * delete deletable records. On the first run, completion tokens are parsed to determine the
   * deletable-before timestamp. On subsequent runs, the timestamp is restored from the checkpoint.
   *
   * @throws Exception if token validation, scanning, or state persistence fails
   */
  public void execute() throws Exception {
    CoordinatorCleanupStateManager stateManager = new CoordinatorCleanupStateManager(checkpointDir);
    CoordinatorCleanupState state = loadOrInitializeState(stateManager);

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
   * Loads persisted state if available; otherwise parses tokens, computes the safe-deletion
   * boundary, and persists the initial state.
   */
  private CoordinatorCleanupState loadOrInitializeState(
      CoordinatorCleanupStateManager stateManager) {
    CoordinatorCleanupState state = stateManager.load();

    if (state != null) {
      // The tool is resumable and idempotent: re-invoking the same command (including the
      // completion tokens) with an existing checkpoint simply resumes the previous run. The
      // specified tokens are ignored so that the deletion boundary stays fixed across retries.
      if (ledgerTokenString != null || auditorTokenString != null) {
        logger.warn("A checkpoint already exists; the specified completion tokens are ignored.");
      }

      if (state.isCompleted()) {
        logger.info("Found existing checkpoint data; the cleanup has already been completed.");
      } else {
        logger.info(
            "Found existing checkpoint data; resuming the previous run. "
                + "Records created before {} will be deleted.",
            Instant.ofEpochMilli(state.getDeletableBeforeMs()));
      }

      return state;
    }

    if (ledgerTokenString == null || auditorTokenString == null) {
      throw new CoordinatorStateDeleterException(
          CoordinatorStateDeleterError.BOTH_COMPLETION_TOKENS_REQUIRED);
    }

    long deletableBeforeMs = parseAndComputeDeletableBeforeMs();
    state = new CoordinatorCleanupState(deletableBeforeMs);
    stateManager.persist(state);
    logger.info(
        "Starting a new run. Records created before {} will be deleted.",
        Instant.ofEpochMilli(deletableBeforeMs));

    return state;
  }

  /** Parses and validates both tokens, then returns the earlier guarantee timestamp. */
  private long parseAndComputeDeletableBeforeMs() {
    CompletionToken ledgerToken = CompletionToken.decode(ledgerTokenString);
    if (ledgerToken.getServerType() != CompletionToken.ServerType.LEDGER) {
      throw new CoordinatorStateDeleterException(
          CoordinatorStateDeleterError.LEDGER_TOKEN_WRONG_SERVER_TYPE, ledgerToken.getServerType());
    }

    CompletionToken auditorToken = CompletionToken.decode(auditorTokenString);
    if (auditorToken.getServerType() != CompletionToken.ServerType.AUDITOR) {
      throw new CoordinatorStateDeleterException(
          CoordinatorStateDeleterError.AUDITOR_TOKEN_WRONG_SERVER_TYPE,
          auditorToken.getServerType());
    }

    long ledgerTimestamp = ledgerToken.getStartedAtMs();
    long auditorTimestamp = auditorToken.getStartedAtMs();
    long deletableBeforeMs = Math.min(ledgerTimestamp, auditorTimestamp);

    logger.info(
        "Completion tokens parsed: Ledger started at {}, Auditor started at {}",
        Instant.ofEpochMilli(ledgerTimestamp),
        Instant.ofEpochMilli(auditorTimestamp));

    return deletableBeforeMs;
  }

  /** Scans the coordinator table and deletes deletable records. */
  private void scanAndDelete(CoordinatorCleanupStateManager stateManager, long deletableBeforeMs)
      throws Exception {
    Path scanCheckpointDir = stateManager.getStateDir();

    logger.info("Starting to scan the coordinator table");

    DeleteCoordinatorStateHandler handler =
        new DeleteCoordinatorStateHandler(
            new RecordDeletionChecker(deletableBeforeMs), recordDeleter);

    try (ResumableScanner scanner = scannerFactory.create(scanCheckpointDir)) {
      ScanResult scanResult = scanner.scan(coordinatorNamespace, Coordinator.TABLE, handler);

      // The deletion count is per run (command execution) only. Tracking a cumulative total across
      // runs would require persisting it on every deletion, so we report only what this run did.
      logger.info("Finished scanning the coordinator table.");
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
