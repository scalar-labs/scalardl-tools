package com.scalar.dl.tools.cleanup;

import com.scalar.db.api.Result;
import com.scalar.dl.tools.common.AuditorInternalValues;
import com.scalar.dl.tools.scan.RecordHandler;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.concurrent.ThreadSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link RecordHandler} for {@code auditor-finalize-records}.
 *
 * <p>For each scanned asset-lock record it decides whether the lock needs finalization ({@link
 * LockStateChecker}) and, if so, finalizes it via {@link LockFinalizer}. A lock that cannot be
 * finalized yet (e.g. a read lock kept fresh by an active reader) is appended to an {@link
 * AppendOnlyLog} instead of aborting the scan; {@link AuditorFinalizeOrchestrator} retries every
 * deferred lock once the scan finishes, so a single active lock no longer blocks the sweep.
 */
@ThreadSafe
public final class FinalizeAssetLockHandler implements RecordHandler {

  private static final Logger logger = LoggerFactory.getLogger(FinalizeAssetLockHandler.class);

  /** The number of finalizations between successive progress log lines. */
  private static final long PROGRESS_LOG_INTERVAL = 100_000L;

  private final LockStateChecker stateChecker;
  private final LockFinalizer lockFinalizer;
  private final String namespace;
  private final AppendOnlyLog deferredFinalizations;
  private final AtomicLong finalizedCount = new AtomicLong();

  @SuppressFBWarnings("EI_EXPOSE_REP2")
  public FinalizeAssetLockHandler(
      LockStateChecker stateChecker,
      LockFinalizer lockFinalizer,
      String namespace,
      AppendOnlyLog deferredFinalizations) {
    this.stateChecker = stateChecker;
    this.lockFinalizer = lockFinalizer;
    this.namespace = namespace;
    this.deferredFinalizations = deferredFinalizations;
  }

  @Override
  public void handle(Result record) {
    if (!stateChecker.needsFinalization(record)) {
      return;
    }
    String assetId = record.getText(AuditorInternalValues.ASSET_LOCK_TABLE_ID_COLUMN_NAME);
    if (assetId == null) {
      // The id column is the partition key of the asset_lock table, so it should never be null for
      // a real record.
      throw new IllegalStateException(
          "Column "
              + AuditorInternalValues.ASSET_LOCK_TABLE_ID_COLUMN_NAME
              + " not found in the result");
    }
    if (lockFinalizer.execute(namespace, assetId) == LockFinalizer.Result.NOT_FINALIZED) {
      // The lock is still active. Defer it for a post-scan retry instead of aborting the whole
      // sweep here.
      deferredFinalizations.append(assetId);
      return;
    }

    long finalized = finalizedCount.incrementAndGet();
    if (finalized % PROGRESS_LOG_INTERVAL == 0) {
      logger.info(
          "Finalized {} locks in the asset_lock table in namespace {} so far.",
          finalized,
          namespace);
    }
  }

  /** Returns the number of locks finalized so far for this namespace's asset_lock table. */
  public long getFinalizedCount() {
    return finalizedCount.get();
  }
}
