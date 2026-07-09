package com.scalar.dl.tools.cleanup;

import com.scalar.dl.auditor.ordering.LockRecoveryResult;
import com.scalar.dl.client.service.AuditorClient;
import com.scalar.dl.rpc.AssetLockRecoveryRequest;
import com.scalar.dl.tools.common.CoordinatorStateDeleterError;
import com.scalar.dl.tools.common.CoordinatorStateDeleterException;
import javax.annotation.concurrent.ThreadSafe;

/** Finalizes unreleased asset locks by issuing {@code RecoverAssetLock} RPCs to the Auditor. */
@ThreadSafe
public final class LockFinalizer {

  private final AuditorClient auditorClient;

  public LockFinalizer(AuditorClient auditorClient) {
    this.auditorClient = auditorClient;
  }

  /**
   * Finalizes the lock for the given asset by issuing the synchronous {@code RecoverAssetLock} RPC
   * and returns the {@link Result}. The RPC completes recovery before returning, so the result is
   * authoritative and no re-read is required.
   *
   * <p>{@link Result#NOT_FINALIZED} is returned rather than thrown so that a single still-active
   * lock (e.g. a read lock kept fresh by an active reader) need not abort the run mid-scan; the
   * caller decides how to handle it (see {@link FinalizeAssetLockHandler}).
   *
   * @return {@link Result#FINALIZED} if the lock was already released, or {@link
   *     Result#NOT_FINALIZED} if it is still active and could not be finalized yet
   * @throws CoordinatorStateDeleterException if the RPC returns {@code FAILED}
   */
  public Result execute(String namespace, String assetId) {
    AssetLockRecoveryRequest rpcRequest =
        AssetLockRecoveryRequest.newBuilder().setNamespace(namespace).setAssetId(assetId).build();

    LockRecoveryResult rpcResult = auditorClient.recover(rpcRequest);
    if (rpcResult == LockRecoveryResult.FAILED) {
      throw new CoordinatorStateDeleterException(
          CoordinatorStateDeleterError.RECOVER_ASSET_LOCK_RPC_FAILED, assetId, namespace);
    }
    return rpcResult == LockRecoveryResult.NOT_RECOVERABLE
        ? Result.NOT_FINALIZED
        : Result.FINALIZED;
  }

  /** The result of a finalization attempt. */
  public enum Result {
    /** The lock was finalized: released, or already released / never held. */
    FINALIZED,
    /** The lock could not be finalized because it is still active. */
    NOT_FINALIZED
  }
}
