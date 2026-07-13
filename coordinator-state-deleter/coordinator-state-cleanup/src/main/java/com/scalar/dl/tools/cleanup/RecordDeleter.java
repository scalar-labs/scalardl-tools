package com.scalar.dl.tools.cleanup;

import com.scalar.db.api.Delete;
import com.scalar.db.api.DistributedStorage;
import com.scalar.db.api.Result;
import com.scalar.db.io.Key;
import com.scalar.db.transaction.consensuscommit.CoordinatorStateAccessor;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import javax.annotation.concurrent.ThreadSafe;

/** Deletes coordinator table records using {@link DistributedStorage}. */
@ThreadSafe
public final class RecordDeleter {

  private final DistributedStorage storage;
  private final String coordinatorNamespace;

  @SuppressFBWarnings("EI_EXPOSE_REP2")
  public RecordDeleter(DistributedStorage storage, String coordinatorNamespace) {
    this.storage = storage;
    this.coordinatorNamespace = coordinatorNamespace;
  }

  /** Deletes a coordinator table record. */
  public void execute(Result result) throws Exception {
    storage.delete(buildDelete(result));
  }

  private Delete buildDelete(Result result) {
    @SuppressWarnings("deprecation")
    Key partitionKey =
        result
            .getPartitionKey()
            .orElseThrow(() -> new IllegalArgumentException("Partition key not found in result"));

    return Delete.newBuilder()
        .namespace(coordinatorNamespace)
        .table(CoordinatorStateAccessor.TABLE)
        .partitionKey(partitionKey)
        .build();
  }
}
