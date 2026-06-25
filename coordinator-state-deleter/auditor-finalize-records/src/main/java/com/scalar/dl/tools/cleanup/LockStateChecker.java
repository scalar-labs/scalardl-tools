package com.scalar.dl.tools.cleanup;

import com.scalar.db.api.Result;
import com.scalar.dl.tools.common.AuditorInternalValues;

/** Checks asset-lock state to determine if a lock record needs finalization. */
public final class LockStateChecker {

  private final long guaranteeTimestamp;

  public LockStateChecker(long guaranteeTimestamp) {
    this.guaranteeTimestamp = guaranteeTimestamp;
  }

  /**
   * Returns {@code true} if the lock needs finalization.
   *
   * <p>A released lock ({@code NONE}) never needs finalization. A read lock always does: its {@code
   * last_updated_at} is refreshed every time a new reader joins (while the existing owners' nonces
   * are retained), so it cannot serve as a proxy for a stranded owner's acquisition time — a stale
   * read owner can be masked behind a fresh {@code last_updated_at}. To avoid silently skipping
   * such a lock, every read lock is finalized regardless of its timestamp. A write lock is
   * exclusive and never refreshes its {@code last_updated_at} while held, so the timestamp remains
   * a sound proxy and only write locks last updated before the guarantee timestamp need
   * finalization.
   *
   * @throws IllegalStateException if the record carries an unexpected {@code lock_type} value
   */
  public boolean needsFinalization(Result result) {
    int lockType = result.getInt(AuditorInternalValues.ASSET_LOCK_TABLE_LOCK_TYPE_COLUMN_NAME);
    if (lockType == AuditorInternalValues.LOCK_TYPE_NONE) {
      return false;
    }
    if (lockType == AuditorInternalValues.LOCK_TYPE_READ) {
      return true;
    }
    if (lockType == AuditorInternalValues.LOCK_TYPE_WRITE) {
      long lastUpdatedAt =
          result.getBigInt(AuditorInternalValues.ASSET_LOCK_TABLE_LAST_UPDATED_AT_COLUMN_NAME);
      return lastUpdatedAt < guaranteeTimestamp;
    }
    throw new IllegalStateException(
        String.format("Unexpected lock_type value in asset_lock record: %d", lockType));
  }
}
