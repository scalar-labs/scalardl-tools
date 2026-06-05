package com.scalar.dl.tools.cleanup;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.scalar.db.api.Delete;
import com.scalar.db.api.DistributedStorage;
import com.scalar.db.api.Result;
import com.scalar.db.io.Key;
import com.scalar.db.transaction.consensuscommit.Coordinator;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.concurrent.ThreadSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Concurrently deletes coordinator table records using {@link DistributedStorage}. */
@ThreadSafe
public final class RecordDeleter implements AutoCloseable {

  private static final Logger logger = LoggerFactory.getLogger(RecordDeleter.class);
  private static final int MAX_IN_FLIGHT = 1024;
  private static final int SHUTDOWN_TIMEOUT_SECONDS = 30;

  @SuppressFBWarnings("EI_EXPOSE_REP2")
  private final DistributedStorage storage;

  private final ExecutorService executor;
  private final Semaphore permits;
  private final Queue<Exception> errors;
  private final AtomicLong deletedCount;

  public RecordDeleter(DistributedStorage storage, int threadCount) {
    this.storage = storage;
    this.executor =
        Executors.newFixedThreadPool(
            threadCount,
            new ThreadFactoryBuilder().setNameFormat("delete-worker-%d").setDaemon(true).build());
    this.permits = new Semaphore(MAX_IN_FLIGHT);
    this.errors = new ConcurrentLinkedQueue<>();
    this.deletedCount = new AtomicLong(0);
  }

  /**
   * Submits a coordinator table record for deletion. Uses a semaphore for backpressure so that at
   * most {@link #MAX_IN_FLIGHT} deletions are in flight at any time. The calling thread blocks on
   * {@code permits.acquire()} when the limit is reached and resumes as soon as any single deletion
   * completes.
   */
  public void submit(Result scanResult) throws Exception {
    throwIfFailed();

    permits.acquire();
    boolean submitted = false;
    try {
      executor.execute(
          () -> {
            try {
              storage.delete(createDelete(scanResult));
              deletedCount.incrementAndGet();
            } catch (Exception e) {
              errors.add(e);
            } finally {
              permits.release();
            }
          });
      submitted = true;
    } finally {
      if (!submitted) {
        permits.release();
      }
    }
  }

  /** Waits for all submitted deletions to complete. */
  public void awaitCompletion() throws Exception {
    // Block until all workers have released their permits, i.e. all tasks are done
    permits.acquire(MAX_IN_FLIGHT);
    // Return permits so that submit()/awaitCompletion() can be called again
    permits.release(MAX_IN_FLIGHT);

    throwIfFailed();
  }

  private void throwIfFailed() throws Exception {
    Exception first = errors.poll();
    if (first == null) {
      return;
    }
    // Attach remaining worker errors via getSuppressed()
    Exception e;
    while ((e = errors.poll()) != null) {
      first.addSuppressed(e);
    }
    throw first;
  }

  private Delete createDelete(Result result) {
    Key partitionKey =
        result
            .getPartitionKey()
            .orElseThrow(
                () -> new IllegalArgumentException("Partition key not found in scan result"));

    return Delete.newBuilder()
        .namespace(Coordinator.NAMESPACE)
        .table(Coordinator.TABLE)
        .partitionKey(partitionKey)
        .build();
  }

  /** Returns the count of successfully deleted records. */
  public long getDeletedCount() {
    return deletedCount.get();
  }

  @Override
  public void close() {
    executor.shutdownNow();
    try {
      if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        logger.warn(
            "Delete executor did not terminate within {} seconds", SHUTDOWN_TIMEOUT_SECONDS);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
