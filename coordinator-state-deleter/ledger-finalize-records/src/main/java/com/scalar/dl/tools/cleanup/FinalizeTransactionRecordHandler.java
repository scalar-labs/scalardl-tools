package com.scalar.dl.tools.cleanup;

import com.scalar.db.api.Result;
import com.scalar.db.util.ScalarDbUtils;
import com.scalar.dl.tools.scan.RecordHandler;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.concurrent.ThreadSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link RecordHandler} for {@code ledger-finalize-records}.
 *
 * <p>For each scanned record it decides whether the record needs finalization ({@link
 * RecordStateChecker}) and, if so, finalizes it via ScalarDB recovery ({@link RecordFinalizer}).
 */
@ThreadSafe
public final class FinalizeTransactionRecordHandler implements RecordHandler {

  private static final Logger logger =
      LoggerFactory.getLogger(FinalizeTransactionRecordHandler.class);

  /** The number of finalizations between successive progress log lines. */
  private static final long PROGRESS_LOG_INTERVAL = 100_000L;

  private final RecordStateChecker stateChecker;
  private final RecordFinalizer recordFinalizer;
  private final String namespace;
  private final String tableName;
  private final AtomicLong finalizedCount = new AtomicLong();

  public FinalizeTransactionRecordHandler(
      RecordStateChecker stateChecker,
      RecordFinalizer recordFinalizer,
      String namespace,
      String tableName) {
    this.stateChecker = stateChecker;
    this.recordFinalizer = recordFinalizer;
    this.namespace = namespace;
    this.tableName = tableName;
  }

  @Override
  public void handle(Result record) throws Exception {
    if (!stateChecker.needsFinalization(record)) {
      return;
    }
    recordFinalizer.execute(namespace, tableName, record);
    long finalized = finalizedCount.incrementAndGet();
    if (finalized % PROGRESS_LOG_INTERVAL == 0) {
      logger.info(
          "Finalized {} records in table {} so far.",
          finalized,
          ScalarDbUtils.getFullTableName(namespace, tableName));
    }
  }

  /** Returns the number of records finalized so far for this table. */
  public long getFinalizedCount() {
    return finalizedCount.get();
  }
}
