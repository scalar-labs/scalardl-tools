package com.scalar.dl.tools.cleanup;

import com.scalar.db.api.Result;
import com.scalar.dl.tools.common.AuditorInternalValues;

/** Decides whether a {@code request_proof} record is safe to delete. */
public final class RequestProofDeletionChecker {

  private final long deletableBeforeMs;

  public RequestProofDeletionChecker(long deletableBeforeMs) {
    this.deletableBeforeMs = deletableBeforeMs;
  }

  /**
   * Returns {@code true} if the record was registered strictly before the deletable-before
   * boundary. The {@code registered_at} column is immutable (written once at bind time), so it acts
   * as the record's creation timestamp. See {@link RequestProofCleanupOrchestrator} for why a
   * record registered before the boundary is safe to delete.
   */
  public boolean isDeletable(Result result) {
    if (result.isNull(AuditorInternalValues.REQUEST_PROOF_TABLE_REGISTERED_AT_COLUMN_NAME)) {
      return false;
    }
    return result.getBigInt(AuditorInternalValues.REQUEST_PROOF_TABLE_REGISTERED_AT_COLUMN_NAME)
        < deletableBeforeMs;
  }
}
