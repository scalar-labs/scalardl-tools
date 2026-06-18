package com.scalar.dl.tools.cleanup;

import com.scalar.db.api.Result;
import com.scalar.dl.tools.scan.RecordHandler;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.concurrent.ThreadSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link RecordHandler} for {@code coordinator-state-cleanup}.
 *
 * <p>For each scanned coordinator-state record it decides whether the record is deletable ({@link
 * RecordDeletionChecker}) and, if so, deletes it ({@link RecordDeleter}).
 */
@ThreadSafe
public final class DeleteCoordinatorStateHandler implements RecordHandler {

  private static final Logger logger = LoggerFactory.getLogger(DeleteCoordinatorStateHandler.class);

  /** The number of deletions between successive progress log lines. */
  private static final long PROGRESS_LOG_INTERVAL = 100_000L;

  private final RecordDeletionChecker deletionChecker;
  private final RecordDeleter recordDeleter;
  private final AtomicLong deletedCount = new AtomicLong();

  public DeleteCoordinatorStateHandler(
      RecordDeletionChecker deletionChecker, RecordDeleter recordDeleter) {
    this.deletionChecker = deletionChecker;
    this.recordDeleter = recordDeleter;
  }

  @Override
  public void handle(Result record) throws Exception {
    if (!deletionChecker.isDeletable(record)) {
      return;
    }
    recordDeleter.execute(record);
    long deleted = deletedCount.incrementAndGet();
    if (deleted % PROGRESS_LOG_INTERVAL == 0) {
      logger.info("Deleted {} records in this run so far.", deleted);
    }
  }

  /** Returns the number of records deleted. */
  public long getDeletedCount() {
    return deletedCount.get();
  }
}
