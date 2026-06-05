package com.scalar.dl.tools.cleanup;

import com.google.common.annotations.VisibleForTesting;
import com.scalar.db.api.DistributedStorage;
import com.scalar.db.config.DatabaseConfig;
import com.scalar.db.service.StorageFactory;
import com.scalar.db.transaction.consensuscommit.Attribute;
import com.scalar.db.transaction.consensuscommit.Coordinator;
import com.scalar.dl.tools.common.CompletionToken;
import com.scalar.dl.tools.scan.ResumableScanner;
import com.scalar.dl.tools.scan.ResumableScannerFactory;
import com.scalar.dl.tools.scan.ScanResult;
import java.nio.file.Path;
import java.util.Properties;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrates the {@code coordinator-state-cleanup} workflow.
 *
 * <p>This class receives two completion tokens (from {@code ledger-finalize-records} and {@code
 * auditor-finalize-records}), derives the deletable-before timestamp as the earlier of the two
 * guarantee timestamps, scans the coordinator table, and deletes every record whose {@code
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
  private final int workerThreads;
  private final String ledgerTokenString;
  private final String auditorTokenString;
  private final RecordDeleterFactory recordDeleterFactory;

  @VisibleForTesting
  CoordinatorCleanupOrchestrator(
      DistributedStorage storage,
      ResumableScannerFactory scannerFactory,
      Path checkpointDir,
      int workerThreads,
      String ledgerTokenString,
      String auditorTokenString) {
    this(
        storage,
        scannerFactory,
        checkpointDir,
        workerThreads,
        ledgerTokenString,
        auditorTokenString,
        RecordDeleter::new);
  }

  @VisibleForTesting
  CoordinatorCleanupOrchestrator(
      DistributedStorage storage,
      ResumableScannerFactory scannerFactory,
      Path checkpointDir,
      int workerThreads,
      String ledgerTokenString,
      String auditorTokenString,
      RecordDeleterFactory recordDeleterFactory) {
    this.storage = storage;
    this.scannerFactory = scannerFactory;
    this.checkpointDir = checkpointDir;
    this.workerThreads = workerThreads;
    this.ledgerTokenString = ledgerTokenString;
    this.auditorTokenString = auditorTokenString;
    this.recordDeleterFactory = recordDeleterFactory;
  }

  /**
   * Creates an orchestrator from raw ScalarDB properties.
   *
   * @param props properties containing ScalarDB database configuration information
   * @param checkpointDir root directory for checkpoint state
   * @param workerThreads number of threads for deleting records ({@code null} = number of available
   *     CPU cores)
   * @param ledgerTokenString the ledger completion token
   * @param auditorTokenString the auditor completion token
   * @return a new orchestrator instance
   */
  public static CoordinatorCleanupOrchestrator create(
      Properties props,
      Path checkpointDir,
      @Nullable Integer workerThreads,
      String ledgerTokenString,
      String auditorTokenString) {
    DatabaseConfig dbConfig = new DatabaseConfig(props);
    DistributedStorage storage = StorageFactory.create(props).getStorage();

    int resolvedWorkerThreads =
        workerThreads != null ? workerThreads : Runtime.getRuntime().availableProcessors();
    ResumableScannerFactory scannerFactory = new ResumableScannerFactory(dbConfig);
    return new CoordinatorCleanupOrchestrator(
        storage,
        scannerFactory,
        checkpointDir,
        resolvedWorkerThreads,
        ledgerTokenString,
        auditorTokenString);
  }

  /**
   * Executes the full cleanup workflow: parse and validate the completion tokens, determine the
   * deletable-before timestamp, scan the coordinator table, and delete deletable records.
   *
   * @return the number of deleted records
   * @throws Exception if token validation, scanning, or state persistence fails
   */
  public long execute() throws Exception {
    CoordinatorCleanupStateManager stateManager = new CoordinatorCleanupStateManager(checkpointDir);
    CoordinatorCleanupState state = loadOrInitializeState(stateManager);

    long deletableBeforeMs = state.getDeletableBeforeMs();
    long deletedCount = scanAndDelete(stateManager, deletableBeforeMs);

    logger.info("Cleanup complete: {} records deleted", deletedCount);
    return deletedCount;
  }

  /**
   * Loads persisted state if available; otherwise parses tokens, computes the safe-deletion
   * boundary, and persists the initial state.
   */
  private CoordinatorCleanupState loadOrInitializeState(
      CoordinatorCleanupStateManager stateManager) {
    CoordinatorCleanupState state = stateManager.load();
    if (state != null) {
      logger.info("Resumed state: deletable_before_ms={}", state.getDeletableBeforeMs());
      return state;
    }

    long deletableBeforeMs = parseAndComputeDeletableBeforeMs();
    state = new CoordinatorCleanupState(deletableBeforeMs);
    stateManager.persist(state);
    logger.info("Initialized state: deletable_before_ms={}", deletableBeforeMs);
    return state;
  }

  /** Parses and validates both tokens, then returns the earlier guarantee timestamp. */
  private long parseAndComputeDeletableBeforeMs() {
    CompletionToken ledgerToken = CompletionToken.decode(ledgerTokenString);
    if (ledgerToken.getServerType() != CompletionToken.ServerType.LEDGER) {
      throw new IllegalArgumentException(
          "Ledger token has wrong server type: " + ledgerToken.getServerType());
    }

    CompletionToken auditorToken = CompletionToken.decode(auditorTokenString);
    if (auditorToken.getServerType() != CompletionToken.ServerType.AUDITOR) {
      throw new IllegalArgumentException(
          "Auditor token has wrong server type: " + auditorToken.getServerType());
    }

    long ledgerTimestamp = ledgerToken.getStartedAtMs();
    long auditorTimestamp = auditorToken.getStartedAtMs();
    long deletableBeforeMs = Math.min(ledgerTimestamp, auditorTimestamp);
    logger.info(
        "Tokens parsed: ledger={}, auditor={}, deletable_before_ms={}",
        ledgerTimestamp,
        auditorTimestamp,
        deletableBeforeMs);
    return deletableBeforeMs;
  }

  /** Scans the coordinator table and deletes deletable records. */
  private long scanAndDelete(CoordinatorCleanupStateManager stateManager, long deletableBeforeMs)
      throws Exception {
    Path scanCheckpointDir = stateManager.getStateDir();

    logger.info("Scanning coordinator table");

    try (ResumableScanner scanner = scannerFactory.create(scanCheckpointDir);
        RecordDeleter deleter = recordDeleterFactory.create(storage, workerThreads)) {

      ScanResult scanResult =
          scanner.scan(
              Coordinator.NAMESPACE,
              Coordinator.TABLE,
              result -> {
                if (!result.isNull(Attribute.CREATED_AT)
                    && result.getBigInt(Attribute.CREATED_AT) < deletableBeforeMs) {
                  try {
                    deleter.submit(result);
                  } catch (Exception e) {
                    throw new RuntimeException(e);
                  }
                }
              });
      deleter.awaitCompletion();

      logger.info(
          "Scan complete: scanned={}, deleted={}",
          scanResult.getTotalScanned(),
          deleter.getDeletedCount());

      return deleter.getDeletedCount();
    }
  }

  @Override
  public void close() {
    try {
      storage.close();
    } catch (Exception e) {
      logger.warn("Failed to close DistributedStorage.", e);
    }
  }

  @VisibleForTesting
  @FunctionalInterface
  interface RecordDeleterFactory {
    RecordDeleter create(DistributedStorage storage, int workerThreads);
  }
}
