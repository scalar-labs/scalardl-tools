package com.scalar.dl.tools.cleanup;

import com.scalar.db.api.Result;
import com.scalar.db.api.TransactionState;
import com.scalar.db.transaction.consensuscommit.TransactionResult;

/** Checks ConsensusCommit transaction state to determine if a record needs finalization. */
public final class RecordStateChecker {

  private final long guaranteeTimestamp;

  public RecordStateChecker(long guaranteeTimestamp) {
    this.guaranteeTimestamp = guaranteeTimestamp;
  }

  /**
   * Returns {@code true} if the record is in a non-terminal state (PREPARED or DELETED) and was
   * prepared before the guarantee timestamp.
   */
  public boolean needsFinalization(Result result) {
    TransactionResult txResult = new TransactionResult(result);
    TransactionState state = txResult.getState();
    if (state != TransactionState.PREPARED && state != TransactionState.DELETED) {
      return false;
    }
    return txResult.getPreparedAt() < guaranteeTimestamp;
  }
}
