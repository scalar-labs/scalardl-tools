package com.scalar.dl.tools.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.scalar.db.api.Result;
import com.scalar.dl.auditor.ordering.LockRecoveryResult;
import com.scalar.dl.client.service.AuditorClient;
import com.scalar.dl.rpc.AssetLockRecoveryRequest;
import com.scalar.dl.tools.common.AuditorInternalValues;
import com.scalar.dl.tools.common.ScalarDlCleanupError;
import com.scalar.dl.tools.common.ScalarDlCleanupException;
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

  private Result createScanResult() {
    Result result = mock(Result.class);
    when(result.getText(AuditorInternalValues.ASSET_LOCK_TABLE_ID_COLUMN_NAME))
        .thenReturn("asset1");
    return result;
  }

  @Test
  void execute_shouldSendRpcWithNamespaceAndAssetId() {
    // Arrange
    when(auditorClient.recover(any(AssetLockRecoveryRequest.class)))
        .thenReturn(LockRecoveryResult.SUCCEEDED);

    // Act
    finalizer.execute("ns1", createScanResult());

    // Assert — the RPC carries the namespace and the asset id from the scan result.
    ArgumentCaptor<AssetLockRecoveryRequest> captor =
        ArgumentCaptor.forClass(AssetLockRecoveryRequest.class);
    verify(auditorClient).recover(captor.capture());
    assertThat(captor.getValue().getNamespace()).isEqualTo("ns1");
    assertThat(captor.getValue().getAssetId()).isEqualTo("asset1");
  }

  @Test
  void execute_notNeededGiven_shouldNotThrow() {
    // Arrange — NOT_NEEDED means the lock is already released.
    when(auditorClient.recover(any(AssetLockRecoveryRequest.class)))
        .thenReturn(LockRecoveryResult.NOT_NEEDED);

    // Act & Assert — no exception is thrown.
    finalizer.execute("default", createScanResult());
    verify(auditorClient).recover(any(AssetLockRecoveryRequest.class));
  }

  @Test
  void execute_assetIdMissingGiven_shouldThrowExceptionWithoutCallingRecover() {
    // Arrange — the scan result has no id column.
    Result result = mock(Result.class);
    when(result.getText(AuditorInternalValues.ASSET_LOCK_TABLE_ID_COLUMN_NAME)).thenReturn(null);

    // Act & Assert — the RPC is never issued for a record without an asset id.
    assertThatThrownBy(() -> finalizer.execute("default", result))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(AuditorInternalValues.ASSET_LOCK_TABLE_ID_COLUMN_NAME);
    verify(auditorClient, never()).recover(any(AssetLockRecoveryRequest.class));
  }

  @Test
  void execute_recoverThrowsExceptionGiven_shouldPropagateException() {
    // Arrange
    when(auditorClient.recover(any(AssetLockRecoveryRequest.class)))
        .thenThrow(new RuntimeException("RPC unavailable"));

    // Act & Assert
    assertThatThrownBy(() -> finalizer.execute("default", createScanResult()))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("RPC unavailable");
  }

  @Test
  void execute_rpcFailedGiven_shouldThrowException() {
    // Arrange
    when(auditorClient.recover(any(AssetLockRecoveryRequest.class)))
        .thenReturn(LockRecoveryResult.FAILED);

    // Act & Assert
    assertThatThrownBy(() -> finalizer.execute("default", createScanResult()))
        .isInstanceOf(ScalarDlCleanupException.class)
        .hasMessageContaining(ScalarDlCleanupError.RECOVER_ASSET_LOCK_RPC_FAILED.buildCode());
  }

  @Test
  void execute_rpcNotRecoverableGiven_shouldThrowException() {
    // Arrange
    when(auditorClient.recover(any(AssetLockRecoveryRequest.class)))
        .thenReturn(LockRecoveryResult.NOT_RECOVERABLE);

    // Act & Assert
    assertThatThrownBy(() -> finalizer.execute("default", createScanResult()))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("NOT_RECOVERABLE");
  }
}
