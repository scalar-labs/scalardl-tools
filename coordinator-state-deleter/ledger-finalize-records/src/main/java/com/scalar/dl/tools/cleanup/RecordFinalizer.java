package com.scalar.dl.tools.cleanup;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.scalar.db.api.DistributedTransactionManager;
import com.scalar.db.api.Get;
import com.scalar.db.api.GetBuilder;
import com.scalar.db.api.Result;
import com.scalar.db.common.TransactionExecutor;
import com.scalar.db.io.Key;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Concurrently finalizes non-terminal records by triggering ScalarDB lazy recovery. */
@ThreadSafe
public final class RecordFinalizer implements AutoCloseable {

  private static final Logger logger = LoggerFactory.getLogger(RecordFinalizer.class);
  private static final int COMPLETION_BATCH_SIZE = 1024;
  private static final int SHUTDOWN_TIMEOUT_SECONDS = 30;

  private final DistributedTransactionManager manager;
  private final ExecutorService executor;
  private final List<Future<Void>> futures;
  private final AtomicLong finalizedCount;
  private Throwable deferredFailure;

  public RecordFinalizer(DistributedTransactionManager manager, int threadCount) {
    this.manager = manager;
    this.executor =
        Executors.newFixedThreadPool(
            threadCount,
            new ThreadFactoryBuilder().setNameFormat("finalize-worker-%d").setDaemon(true).build());
    this.futures = new ArrayList<>();
    this.finalizedCount = new AtomicLong(0);
  }

  private static Get buildGet(
      String namespace, String tableName, Key partitionKey, @Nullable Key clusteringKey) {
    GetBuilder.BuildableGet builder =
        Get.newBuilder().namespace(namespace).table(tableName).partitionKey(partitionKey);
    if (clusteringKey != null) {
      builder = builder.clusteringKey(clusteringKey);
    }
    return builder.build();
  }

  /**
   * Submit a record for finalization. Synchronized because multiple scan worker threads (one per
   * Cosmos DB physical partition) call this method concurrently via the scan consumer callback.
   */
  public synchronized void submit(String namespace, String tableName, Result scanResult) {
    Key partitionKey =
        scanResult
            .getPartitionKey()
            .orElseThrow(() -> new IllegalStateException("Partition key not found in scan result"));
    @Nullable Key clusteringKey = scanResult.getClusteringKey().orElse(null);
    Get get = buildGet(namespace, tableName, partitionKey, clusteringKey);
    futures.add(
        executor.submit(
            () -> {
              triggerLazyRecovery(get);
              finalizedCount.incrementAndGet();
              return null;
            }));
    if (futures.size() >= COMPLETION_BATCH_SIZE) {
      awaitCompletionQuietly();
    }
  }

  /** Waits for all submitted finalization to complete. Propagates the first failure. */
  public synchronized void awaitCompletion() throws Exception {
    awaitCompletionQuietly();
    Throwable failure = deferredFailure;
    deferredFailure = null;
    if (failure instanceof Error) {
      throw (Error) failure;
    } else if (failure instanceof Exception) {
      throw (Exception) failure;
    }
  }

  /**
   * Waits for all pending futures to complete, accumulating errors into {@link #deferredFailure}
   * without throwing.
   */
  private void awaitCompletionQuietly() {
    for (Future<Void> future : futures) {
      try {
        future.get();
      } catch (ExecutionException e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        if (deferredFailure == null) {
          deferredFailure = cause;
        } else {
          deferredFailure.addSuppressed(cause);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        for (Future<Void> remaining : futures) {
          remaining.cancel(true);
        }
        if (deferredFailure == null) {
          deferredFailure = e;
        } else {
          deferredFailure.addSuppressed(e);
        }
        break;
      }
    }
    futures.clear();
  }

  /** Triggers ScalarDB lazy recovery via {@code get()}. */
  private void triggerLazyRecovery(Get get) throws Exception {
    TransactionExecutor.executeWithRetries(
        manager,
        tx -> {
          tx.get(get);
        });
  }

  /** Returns the count of successfully finalized records. */
  public long getFinalizedCount() {
    return finalizedCount.get();
  }

  @Override
  public void close() {
    executor.shutdownNow();
    try {
      if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        logger.warn(
            "Finalize executor did not terminate within {} seconds", SHUTDOWN_TIMEOUT_SECONDS);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
