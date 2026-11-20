package com.scalar.dl.tools.cleanup;

import com.scalar.db.api.Result;
import com.scalar.dl.tools.common.AuditorInternalValues;
import com.scalar.dl.tools.scan.RecordHandler;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.concurrent.ThreadSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link RecordHandler} for verifying that no write lock remains in an {@code asset_lock} table.
 *
 * <p>A record whose lock type is {@code WRITE} is a detection, whenever it was last updated.
 * Released locks ({@code NONE}) and read locks pass.
 *
 * <p>The handler stops the scan at the first detection. Some scanners invoke the handler from
 * several workers and wait for all of them, so failing only the worker that made the detection
 * would let the others scan their ranges to the end. The handler therefore remembers the detection
 * and fails every later call, whatever the record is, so that each worker stops at its next record.
 * Since the scan then ends with an exception, the caller must take the verdict from {@link
 * #isDetected()}, not from the exception.
 */
@ThreadSafe
public final class VerifyAssetLockHandler implements RecordHandler {

  private static final Logger logger = LoggerFactory.getLogger(VerifyAssetLockHandler.class);

  /** The number of scanned records between successive progress log lines. */
  private static final long PROGRESS_LOG_INTERVAL = 100_000L;

  private final String physicalNamespace;
  private final AtomicBoolean detected = new AtomicBoolean();
  private final AtomicLong checkedCount = new AtomicLong();

  /**
   * @param physicalNamespace the ScalarDB namespace of the {@code asset_lock} table being scanned
   */
  public VerifyAssetLockHandler(String physicalNamespace) {
    this.physicalNamespace = physicalNamespace;
  }

  /**
   * Checks a single asset-lock record.
   *
   * @throws IllegalStateException if the record carries an unexpected {@code lock_type} value
   * @throws Exception if the record is a write lock, or if a write lock was detected by an earlier
   *     or concurrent call. A new instance is thrown on each call, because a scanner may attach the
   *     failures of its workers to one another as suppressed exceptions.
   */
  @Override
  public void handle(Result record) throws Exception {
    if (detected.get()) {
      throw new WriteLockDetectedException(
          "The scan was stopped because a write lock was detected in the asset_lock table in"
              + " namespace "
              + physicalNamespace);
    }

    int lockType = record.getInt(AuditorInternalValues.ASSET_LOCK_TABLE_LOCK_TYPE_COLUMN_NAME);
    if (lockType == AuditorInternalValues.LOCK_TYPE_WRITE) {
      detected.set(true);
      String assetId = record.getText(AuditorInternalValues.ASSET_LOCK_TABLE_ID_COLUMN_NAME);
      long lastUpdatedAt =
          record.getBigInt(AuditorInternalValues.ASSET_LOCK_TABLE_LAST_UPDATED_AT_COLUMN_NAME);
      logger.warn(
          "A write lock remains in the asset_lock table in namespace {}: asset ID {}, last updated"
              + " at {} ({} ms).",
          physicalNamespace,
          assetId,
          Instant.ofEpochMilli(lastUpdatedAt),
          lastUpdatedAt);
      throw new WriteLockDetectedException(
          "A write lock remains in the asset_lock table in namespace "
              + physicalNamespace
              + ": asset ID "
              + assetId);
    }
    if (lockType != AuditorInternalValues.LOCK_TYPE_NONE
        && lockType != AuditorInternalValues.LOCK_TYPE_READ) {
      throw new IllegalStateException(
          String.format("Unexpected lock_type value in asset_lock record: %d", lockType));
    }

    long checked = checkedCount.incrementAndGet();
    if (checked % PROGRESS_LOG_INTERVAL == 0) {
      logger.info(
          "Checked {} records in the asset_lock table in namespace {} so far.",
          checked,
          physicalNamespace);
    }
  }

  /** Returns {@code true} if a write lock has been detected by any call so far. */
  public boolean isDetected() {
    return detected.get();
  }

  /** Thrown to stop the scan once a write lock has been detected. */
  private static final class WriteLockDetectedException extends Exception {

    private static final long serialVersionUID = 1L;

    WriteLockDetectedException(String message) {
      super(message);
    }
  }
}
