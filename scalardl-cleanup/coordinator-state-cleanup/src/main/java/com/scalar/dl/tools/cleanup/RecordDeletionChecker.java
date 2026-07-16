package com.scalar.dl.tools.cleanup;

import com.scalar.db.api.Result;
import com.scalar.db.transaction.consensuscommit.Attribute;

/** Decides whether a coordinator-state record is safe to delete. */
public final class RecordDeletionChecker {

  private final long deletableBeforeMs;

  public RecordDeletionChecker(long deletableBeforeMs) {
    this.deletableBeforeMs = deletableBeforeMs;
  }

  /**
   * Returns {@code true} if the record was created strictly before the deletable-before boundary.
   */
  public boolean isDeletable(Result result) {
    if (result.isNull(Attribute.CREATED_AT)) {
      return false;
    }
    return result.getBigInt(Attribute.CREATED_AT) < deletableBeforeMs;
  }
}
