package com.scalar.dl.tools.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.scalar.dl.auditor.ordering.LockRecoveryResult;
import com.scalar.dl.client.service.AuditorClient;
import com.scalar.dl.rpc.AssetLockRecoveryRequest;
import com.scalar.dl.tools.common.CoordinatorStateDeleterError;
import com.scalar.dl.tools.common.CoordinatorStateDeleterException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class LockFinalizerTest {

  private AuditorClient auditorClient;
  private LockFinalizer finalizer;

  @BeforeEach
  void setUp() {
    auditorClient = mock(AuditorClient.class);
    finalizer = new LockFinalizer(auditorClient);
  }

  @Test
  void execute_succeededGiven_shouldSendRpcWithNamespaceAndAssetIdAndReturnRecovered() {
    // Arrange
    when(auditorClient.recover(any(AssetLockRecoveryRequest.class)))
        .thenReturn(LockRecoveryResult.SUCCEEDED);

    // Act
    LockFinalizer.Result result = finalizer.execute("ns1", "asset1");

    // Assert — the RPC carries the given namespace and asset id, and SUCCEEDED maps to RECOVERED.
    assertThat(result).isEqualTo(LockFinalizer.Result.FINALIZED);
    ArgumentCaptor<AssetLockRecoveryRequest> captor =
        ArgumentCaptor.forClass(AssetLockRecoveryRequest.class);
    verify(auditorClient).recover(captor.capture());
    assertThat(captor.getValue().getNamespace()).isEqualTo("ns1");
    assertThat(captor.getValue().getAssetId()).isEqualTo("asset1");
  }

  @Test
  void execute_notNeededGiven_shouldReturnRecovered() {
    // Arrange — NOT_NEEDED means the lock is already released, which is also RECOVERED.
    when(auditorClient.recover(any(AssetLockRecoveryRequest.class)))
        .thenReturn(LockRecoveryResult.NOT_NEEDED);

    // Act & Assert
    assertThat(finalizer.execute("default", "asset1")).isEqualTo(LockFinalizer.Result.FINALIZED);
  }

  @Test
  void execute_notRecoverableGiven_shouldReturnNotRecoverableWithoutThrowing() {
    // Arrange — NOT_RECOVERABLE means the lock is still active; the caller decides what to do.
    when(auditorClient.recover(any(AssetLockRecoveryRequest.class)))
        .thenReturn(LockRecoveryResult.NOT_RECOVERABLE);

    // Act & Assert — the result is returned rather than aborting the run here.
    assertThat(finalizer.execute("default", "asset1"))
        .isEqualTo(LockFinalizer.Result.NOT_FINALIZED);
  }

  @Test
  void execute_recoverThrowsExceptionGiven_shouldPropagateException() {
    // Arrange
    when(auditorClient.recover(any(AssetLockRecoveryRequest.class)))
        .thenThrow(new RuntimeException("RPC unavailable"));

    // Act & Assert
    assertThatThrownBy(() -> finalizer.execute("default", "asset1"))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("RPC unavailable");
  }

  @Test
  void execute_rpcFailedGiven_shouldThrowException() {
    // Arrange
    when(auditorClient.recover(any(AssetLockRecoveryRequest.class)))
        .thenReturn(LockRecoveryResult.FAILED);

    // Act & Assert
    assertThatThrownBy(() -> finalizer.execute("default", "asset1"))
        .isInstanceOf(CoordinatorStateDeleterException.class)
        .hasMessageContaining(
            CoordinatorStateDeleterError.RECOVER_ASSET_LOCK_RPC_FAILED.buildCode());
  }
}
