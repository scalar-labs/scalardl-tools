package com.scalar.dl.tools.cleanup;

import com.scalar.db.api.DistributedTransactionManager;
import com.scalar.db.api.Result;
import com.scalar.db.io.Key;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Finalizes non-terminal records by triggering ScalarDB recovery. */
@ThreadSafe
public final class RecordFinalizer {

  private static final Logger logger = LoggerFactory.getLogger(RecordFinalizer.class);

  private final DistributedTransactionManager manager;

  public RecordFinalizer(DistributedTransactionManager manager) {
    this.manager = manager;
  }

  /**
   * Finalizes a record by triggering ScalarDB recovery.
   *
   * @return {@code true} if the record was finalized (recovered to a terminal state), or {@code
   *     false} if it was not yet recoverable and was skipped
   */
  public boolean execute(String namespace, String tableName, Result result) throws Exception {
    @SuppressWarnings("deprecation")
    Key partitionKey =
        result
            .getPartitionKey()
            .orElseThrow(() -> new IllegalArgumentException("Partition key not found in result"));
    @SuppressWarnings("deprecation")
    @Nullable
    Key clusteringKey = result.getClusteringKey().orElse(null);

    // recoverRecord resolves the record synchronously and returns true once it is terminal. It
    // returns false only when the writer is not yet recoverable (no coordinator state and not yet
    // expired, so it may still be in flight). The coordinator state for such a record is created
    // only after this tool's start time, so it is not a target of coordinator-state-cleanup, which
    // deletes states created before this tool's start time. We can therefore skip it.
    boolean recovered = manager.recoverRecord(namespace, tableName, partitionKey, clusteringKey);
    if (!recovered) {
      logger.info(
          "Record not yet recoverable and outside our deletion window, so skipping."
              + " namespace={}, table={}, partitionKey={}, clusteringKey={}",
          namespace,
          tableName,
          partitionKey,
          clusteringKey);
    }
    return recovered;
  }
}
