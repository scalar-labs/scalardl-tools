package com.scalar.dl.tools.cleanup;

import com.scalar.db.api.Result;
import com.scalar.dl.auditor.ordering.LockRecoveryResult;
import com.scalar.dl.client.service.AuditorClient;
import com.scalar.dl.rpc.AssetLockRecoveryRequest;
import com.scalar.dl.tools.common.AuditorInternalValues;
import com.scalar.dl.tools.common.ScalarDlCleanupError;
import com.scalar.dl.tools.common.ScalarDlCleanupException;
import javax.annotation.concurrent.ThreadSafe;

/** Finalizes unreleased asset locks by issuing {@code RecoverAssetLock} RPCs to the Auditor. */
@ThreadSafe
public final class LockFinalizer {

  private final AuditorClient auditorClient;

  public LockFinalizer(AuditorClient auditorClient) {
    this.auditorClient = auditorClient;
  }

  /**
   * Finalizes a lock record by issuing the synchronous {@code RecoverAssetLock} RPC. The RPC
   * completes recovery before returning, so a {@code SUCCEEDED} or {@code NOT_NEEDED} result
   * confirms the lock is released; no re-read is required.
   */
  public void execute(String namespace, Result result) {
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

    LockRecoveryResult rpcResult = auditorClient.recover(rpcRequest);
    if (rpcResult == LockRecoveryResult.FAILED) {
      throw new ScalarDlCleanupException(
          ScalarDlCleanupError.RECOVER_ASSET_LOCK_RPC_FAILED, assetId, namespace);
    }

    // TODO: NOT_RECOVERABLE means the lock is still active and currently aborts the whole run. A
    //  follow-up will instead defer such locks and retry them after the scan, so a single active
    //  lock no longer blocks finalization.
    if (rpcResult == LockRecoveryResult.NOT_RECOVERABLE) {
      throw new RuntimeException(
          String.format(
              "RecoverAssetLock RPC returned NOT_RECOVERABLE for asset %s in namespace %s",
              assetId, namespace));
    }
    // SUCCEEDED or NOT_NEEDED: the lock has been released.
  }
}
