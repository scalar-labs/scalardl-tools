package com.scalar.dl.tools.cleanup;

import com.scalar.db.api.Result;
import com.scalar.dl.tools.scan.RecordHandler;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.concurrent.ThreadSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link RecordHandler} for {@code request-proof-cleanup}.
 *
 * <p>For each scanned {@code request_proof} record it decides whether the record is deletable
 * ({@link RequestProofDeletionChecker}) and, if so, deletes it ({@link RequestProofDeleter}).
 */
@ThreadSafe
public final class DeleteRequestProofHandler implements RecordHandler {

  private static final Logger logger = LoggerFactory.getLogger(DeleteRequestProofHandler.class);

  /** The number of deletions between successive progress log lines. */
  private static final long PROGRESS_LOG_INTERVAL = 100_000L;

  private final RequestProofDeletionChecker deletionChecker;
  private final RequestProofDeleter recordDeleter;
  private final AtomicLong deletedCount = new AtomicLong();

  public DeleteRequestProofHandler(
      RequestProofDeletionChecker deletionChecker, RequestProofDeleter recordDeleter) {
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
