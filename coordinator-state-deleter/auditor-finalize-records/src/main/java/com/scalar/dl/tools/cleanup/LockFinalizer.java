package com.scalar.dl.tools.cleanup;

import com.scalar.db.api.Result;
import com.scalar.dl.auditor.ordering.LockRecoveryResult;
import com.scalar.dl.client.service.AuditorClient;
import com.scalar.dl.rpc.AssetLockRecoveryRequest;
import com.scalar.dl.tools.common.AuditorInternalValues;

/** Finalizes unreleased asset locks by issuing {@code RecoverAssetLock} RPCs to the Auditor. */
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
      throw new IllegalArgumentException(
          String.format(
              "Column %s not found in result",
              AuditorInternalValues.ASSET_LOCK_TABLE_ID_COLUMN_NAME));
    }

    AssetLockRecoveryRequest rpcRequest =
        AssetLockRecoveryRequest.newBuilder().setNamespace(namespace).setAssetId(assetId).build();

    LockRecoveryResult rpcResult = auditorClient.recover(rpcRequest);
    if (rpcResult == LockRecoveryResult.FAILED) {
      throw new RuntimeException(
          String.format(
              "RecoverAssetLock RPC failed for asset %s in namespace %s", assetId, namespace));
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
