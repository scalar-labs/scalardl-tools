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
    if (!isNonTerminal(result)) {
      return false;
    }
    return new TransactionResult(result).getPreparedAt() < guaranteeTimestamp;
  }

  /**
   * Returns {@code true} if the record is in a non-terminal state (PREPARED or DELETED), regardless
   * of when it was prepared.
   */
  static boolean isNonTerminal(Result result) {
    TransactionState state = new TransactionResult(result).getState();
    return state == TransactionState.PREPARED || state == TransactionState.DELETED;
  }
}
