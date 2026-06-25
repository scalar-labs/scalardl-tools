package com.scalar.dl.tools.cleanup;

import com.scalar.db.api.Delete;
import com.scalar.db.api.DistributedStorage;
import com.scalar.db.api.Result;
import com.scalar.db.io.Key;
import com.scalar.dl.tools.common.AuditorInternalValues;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import javax.annotation.concurrent.ThreadSafe;

/** Deletes {@code request_proof} records using {@link DistributedStorage}. */
@ThreadSafe
public final class RequestProofDeleter {

  private final DistributedStorage storage;
  private final String namespace;

  @SuppressFBWarnings("EI_EXPOSE_REP2")
  public RequestProofDeleter(DistributedStorage storage, String namespace) {
    this.storage = storage;
    this.namespace = namespace;
  }

  /** Deletes a {@code request_proof} record. */
  public void execute(Result result) throws Exception {
    storage.delete(buildDelete(result));
  }

  private Delete buildDelete(Result result) {
    String nonce = result.getText(AuditorInternalValues.REQUEST_PROOF_TABLE_NONCE_COLUMN_NAME);
    if (nonce == null) {
      throw new IllegalArgumentException(
          String.format(
              "Partition key '%s' not found in result",
              AuditorInternalValues.REQUEST_PROOF_TABLE_NONCE_COLUMN_NAME));
    }
    return Delete.newBuilder()
        .namespace(namespace)
        .table(AuditorInternalValues.REQUEST_PROOF_TABLE_NAME)
        .partitionKey(
            Key.ofText(AuditorInternalValues.REQUEST_PROOF_TABLE_NONCE_COLUMN_NAME, nonce))
        .build();
  }
}
