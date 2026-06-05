package com.scalar.dl.tools.cleanup;

import com.scalar.db.api.DistributedTransactionManager;
import com.scalar.db.api.Get;
import com.scalar.db.api.GetBuilder;
import com.scalar.db.api.Result;
import com.scalar.db.common.TransactionExecutor;
import com.scalar.db.io.Key;
import javax.annotation.Nullable;

/** Finalizes non-terminal records by triggering ScalarDB recovery. */
public final class RecordFinalizer {

  private final DistributedTransactionManager manager;
  private final RecordStateChecker stateChecker;
  private long finalizedCount;

  public RecordFinalizer(DistributedTransactionManager manager, RecordStateChecker stateChecker) {
    this.manager = manager;
    this.stateChecker = stateChecker;
  }

  /** Finalizes a record by triggering ScalarDB recovery. */
  public void execute(String namespace, String tableName, Result scanResult) throws Exception {
    Get get = buildGet(namespace, tableName, scanResult);

    recoverSynchronously(get);
    finalizedCount++;
  }

  /** Returns the count of successfully finalized records. */
  public long getFinalizedCount() {
    return finalizedCount;
  }

  private Get buildGet(String namespace, String tableName, Result result) {
    @Deprecated
    Key partitionKey =
        result
            .getPartitionKey()
            .orElseThrow(() -> new IllegalArgumentException("Partition key not found in result"));
    @Deprecated
    @Nullable
    Key clusteringKey = result.getClusteringKey().orElse(null);

    GetBuilder.BuildableGet builder =
        Get.newBuilder().namespace(namespace).table(tableName).partitionKey(partitionKey);
    if (clusteringKey != null) {
      builder = builder.clusteringKey(clusteringKey);
    }
    return builder.build();
  }

  /**
   * TODO: This method will be replaced by a synchronous recovery API provided by ScalarDB later.
   */
  private void recoverSynchronously(Get get) throws Exception {
    int maxRetries = 10;
    long backoffMs = 100;

    for (int attempt = 0; attempt < maxRetries; attempt++) {
      Result[] holder = new Result[1];
      TransactionExecutor.execute(
          manager,
          tx -> {
            holder[0] = tx.get(get).orElse(null);
          });
      if (holder[0] == null || !stateChecker.needsFinalization(holder[0])) {
        return;
      }
      Thread.sleep(backoffMs);
    }

    throw new RuntimeException("Record not finalized after " + maxRetries + " attempts: " + get);
  }
}
