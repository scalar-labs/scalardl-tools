package com.scalar.dl.tools.cleanup;

import com.scalar.db.api.Result;
import com.scalar.db.transaction.consensuscommit.TransactionResult;
import com.scalar.db.util.ScalarDbUtils;
import com.scalar.dl.tools.scan.RecordHandler;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.concurrent.ThreadSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link RecordHandler} for the verification of {@code ledger-finalize-records}.
 *
 * <p>It only reads the records. When it finds a record in a non-terminal state (PREPARED or
 * DELETED), regardless of when the record was prepared, it logs the record, remembers the
 * detection, and throws to stop the scan.
 *
 * <p>A scanner can call the handler from several workers and waits for all of them even after one
 * of them threw. So after the detection, every call throws, also for a terminal record, and each
 * worker stops at its next record. Each call throws a new exception, because a scanner adds the
 * exceptions of the other workers to the first one as suppressed, which the same instance would
 * break.
 *
 * <p>The scan can exit with another exception than the one of the detection, for example, when
 * another worker fails on the storage at the same time. The caller must therefore decide by {@link
 * #isDetected()}, not by the exception.
 */
@ThreadSafe
final class VerifyTransactionRecordHandler implements RecordHandler {

  private static final Logger logger =
      LoggerFactory.getLogger(VerifyTransactionRecordHandler.class);

  /** The number of scanned records between successive progress log lines. */
  private static final long PROGRESS_LOG_INTERVAL = 100_000L;

  private final String namespace;
  private final String tableName;
  private final AtomicBoolean detected = new AtomicBoolean();
  private final AtomicLong scannedCount = new AtomicLong();

  VerifyTransactionRecordHandler(String namespace, String tableName) {
    this.namespace = namespace;
    this.tableName = tableName;
  }

  @Override
  public void handle(Result record) {
    if (detected.get()) {
      throw newDetectedException();
    }
    if (RecordStateChecker.isNonTerminal(record)) {
      detected.set(true);
      logNonTerminalRecord(record);
      throw newDetectedException();
    }
    long scanned = scannedCount.incrementAndGet();
    if (scanned % PROGRESS_LOG_INTERVAL == 0) {
      logger.info(
          "Scanned {} records in table {} so far.",
          scanned,
          ScalarDbUtils.getFullTableName(namespace, tableName));
    }
  }

  /** Returns whether a non-terminal record has been found. */
  boolean isDetected() {
    return detected.get();
  }

  @SuppressWarnings("deprecation")
  private void logNonTerminalRecord(Result record) {
    TransactionResult transactionResult = new TransactionResult(record);
    logger.warn(
        "Found a record in a non-terminal state. namespace={}, table={}, partitionKey={},"
            + " clusteringKey={}, transactionId={}, state={}",
        namespace,
        tableName,
        record.getPartitionKey().orElse(null),
        record.getClusteringKey().orElse(null),
        transactionResult.getId(),
        transactionResult.getState());
  }

  private IllegalStateException newDetectedException() {
    return new IllegalStateException(
        "A record in a non-terminal state was found in the table "
            + ScalarDbUtils.getFullTableName(namespace, tableName)
            + ".");
  }
}
