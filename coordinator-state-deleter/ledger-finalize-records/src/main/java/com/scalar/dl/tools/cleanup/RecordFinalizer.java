package com.scalar.dl.tools.cleanup;

import com.google.common.util.concurrent.Uninterruptibles;
import com.scalar.db.api.DistributedStorage;
import com.scalar.db.api.DistributedTransactionManager;
import com.scalar.db.api.Get;
import com.scalar.db.api.GetBuilder;
import com.scalar.db.api.Result;
import com.scalar.db.common.TransactionExecutor;
import com.scalar.db.io.Key;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

/** Finalizes non-terminal records by triggering ScalarDB recovery. */
@ThreadSafe
public final class RecordFinalizer {

  private final DistributedTransactionManager manager;
  private final DistributedStorage storage;
  private final RecordStateChecker stateChecker;

  @SuppressFBWarnings("EI_EXPOSE_REP2")
  public RecordFinalizer(
      DistributedTransactionManager manager,
      DistributedStorage storage,
      RecordStateChecker stateChecker) {
    this.manager = manager;
    this.storage = storage;
    this.stateChecker = stateChecker;
  }

  /** Finalizes a record by triggering ScalarDB recovery. */
  public void execute(String namespace, String tableName, Result scanResult) throws Exception {
    Get get = buildGet(namespace, tableName, scanResult);

    recoverSynchronously(get);
  }

  private Get buildGet(String namespace, String tableName, Result result) {
    @SuppressWarnings("deprecation")
    Key partitionKey =
        result
            .getPartitionKey()
            .orElseThrow(() -> new IllegalArgumentException("Partition key not found in result"));
    @SuppressWarnings("deprecation")
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
      // Trigger a lazy recovery
      TransactionExecutor.execute(
          manager,
          tx -> {
            tx.get(get);
          });
      // Check the recovery completed
      Optional<Result> result = storage.get(get);
      if (!result.isPresent() || !stateChecker.needsFinalization(result.get())) {
        return;
      }
      Uninterruptibles.sleepUninterruptibly(backoffMs, TimeUnit.MILLISECONDS);
    }

    throw new RuntimeException("Record not finalized after " + maxRetries + " attempts: " + get);
  }
}
