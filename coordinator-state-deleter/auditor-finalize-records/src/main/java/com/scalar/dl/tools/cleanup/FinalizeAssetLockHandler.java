package com.scalar.dl.tools.cleanup;

import com.scalar.db.api.Result;
import com.scalar.dl.tools.scan.RecordHandler;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.concurrent.ThreadSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link RecordHandler} for {@code auditor-finalize-records}.
 *
 * <p>For each scanned asset-lock record it decides whether the lock needs finalization ({@link
 * LockStateChecker}) and, if so, finalizes it by issuing a recovery RPC ({@link LockFinalizer})
 * that carries the namespace.
 */
@ThreadSafe
public final class FinalizeAssetLockHandler implements RecordHandler {

  private static final Logger logger = LoggerFactory.getLogger(FinalizeAssetLockHandler.class);

  /** The number of finalizations between successive progress log lines. */
  private static final long PROGRESS_LOG_INTERVAL = 100_000L;

  private final LockStateChecker stateChecker;
  private final LockFinalizer lockFinalizer;
  private final String namespace;
  private final AtomicLong finalizedCount = new AtomicLong();

  public FinalizeAssetLockHandler(
      LockStateChecker stateChecker, LockFinalizer lockFinalizer, String namespace) {
    this.stateChecker = stateChecker;
    this.lockFinalizer = lockFinalizer;
    this.namespace = namespace;
  }

  @Override
  public void handle(Result record) throws Exception {
    if (!stateChecker.needsFinalization(record)) {
      return;
    }
    lockFinalizer.execute(namespace, record);
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
