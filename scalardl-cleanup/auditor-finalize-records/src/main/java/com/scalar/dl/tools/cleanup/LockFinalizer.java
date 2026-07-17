package com.scalar.dl.tools.cleanup;

import com.google.common.annotations.VisibleForTesting;
import com.scalar.db.api.Result;
import com.scalar.dl.auditor.ordering.LockRecoveryResult;
import com.scalar.dl.client.service.AuditorClient;
import com.scalar.dl.rpc.AssetLockRecoveryRequest;
import com.scalar.dl.tools.common.AuditorInternalValues;
import com.scalar.dl.tools.common.ScalarDlCleanupError;
import com.scalar.dl.tools.common.ScalarDlCleanupException;
import javax.annotation.concurrent.ThreadSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Finalizes unreleased asset locks by issuing {@code RecoverAssetLock} RPCs to the Auditor. */
@ThreadSafe
public final class LockFinalizer {

  @VisibleForTesting static final int DEFAULT_MAX_ATTEMPTS = 3;

  @VisibleForTesting
  static final long DEFAULT_RETRY_INTERVAL_MS = AuditorInternalValues.LOCK_VALID_PERIOD_MS;

  private static final Logger logger = LoggerFactory.getLogger(LockFinalizer.class);

  private final AuditorClient auditorClient;
  private final int maxAttempts;
  private final long retryIntervalMs;

  public LockFinalizer(AuditorClient auditorClient) {
    this(auditorClient, DEFAULT_MAX_ATTEMPTS, DEFAULT_RETRY_INTERVAL_MS);
  }

  @VisibleForTesting
  LockFinalizer(AuditorClient auditorClient, int maxAttempts, long retryIntervalMs) {
    this.auditorClient = auditorClient;
    this.maxAttempts = maxAttempts;
    this.retryIntervalMs = retryIntervalMs;
  }

  /**
   * Finalizes a lock record by issuing the synchronous {@code RecoverAssetLock} RPC. The RPC
   * completes recovery before returning, so a {@code SUCCEEDED} or {@code NOT_NEEDED} result
   * confirms the lock is released; no re-read is required.
   *
   * <p>A {@code NOT_RECOVERABLE} result means the lock is still active (e.g. refreshed by an
   * ongoing reader) and cannot be safely skipped. Rather than abort at once, this method retries up
   * to {@link #maxAttempts} times, sleeping {@link #retryIntervalMs} between attempts to let the
   * lock expire, and aborts only if every attempt still returns {@code NOT_RECOVERABLE}.
   *
   * @throws InterruptedException if the thread is interrupted while sleeping between retries, so
   *     the scan can be canceled promptly.
   */
  public void execute(String namespace, Result result) throws InterruptedException {
    String assetId = result.getText(AuditorInternalValues.ASSET_LOCK_TABLE_ID_COLUMN_NAME);
    if (assetId == null) {
      // The id column is the partition key of the asset_lock table, so it should never be null for
      // a real record; guard against unexpected/corrupted data.
      throw new IllegalStateException(
          "Column "
              + AuditorInternalValues.ASSET_LOCK_TABLE_ID_COLUMN_NAME
              + " not found in the result");
    }

    AssetLockRecoveryRequest rpcRequest =
        AssetLockRecoveryRequest.newBuilder().setNamespace(namespace).setAssetId(assetId).build();

    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
      LockRecoveryResult rpcResult = auditorClient.recover(rpcRequest);

      if (rpcResult == LockRecoveryResult.SUCCEEDED || rpcResult == LockRecoveryResult.NOT_NEEDED) {
        return;
      }

      if (rpcResult == LockRecoveryResult.FAILED) {
        throw new ScalarDlCleanupException(
            ScalarDlCleanupError.RECOVER_ASSET_LOCK_RPC_FAILED, assetId, namespace);
      }

      if (rpcResult != LockRecoveryResult.NOT_RECOVERABLE) {
        throw new IllegalStateException("Unexpected lock recovery result: " + rpcResult);
      }

      // NOT_RECOVERABLE: the lock is not yet expired. Retry unless this was the last attempt.
      if (attempt < maxAttempts) {
        logger.info(
            "Asset {} in namespace {} is still in use. Retrying finalization (attempt {}/{})",
            assetId,
            namespace,
            attempt,
            maxAttempts);
        Thread.sleep(retryIntervalMs);
      }
    }

    throw new ScalarDlCleanupException(
        ScalarDlCleanupError.RECOVER_ASSET_LOCK_NOT_RECOVERABLE, assetId, namespace, maxAttempts);
  }
}
