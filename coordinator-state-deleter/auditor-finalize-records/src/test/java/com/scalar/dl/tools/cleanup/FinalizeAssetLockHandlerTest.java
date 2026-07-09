package com.scalar.dl.tools.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.scalar.db.api.Result;
import com.scalar.dl.tools.common.AuditorInternalValues;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FinalizeAssetLockHandlerTest {

  private static final String NAMESPACE = "default";

  private LockStateChecker stateChecker;
  private LockFinalizer lockFinalizer;
  private AppendOnlyLog deferredFinalizations;
  private FinalizeAssetLockHandler handler;

  @BeforeEach
  void setUp() {
    stateChecker = mock(LockStateChecker.class);
    lockFinalizer = mock(LockFinalizer.class);
    deferredFinalizations = mock(AppendOnlyLog.class);
    handler =
        new FinalizeAssetLockHandler(stateChecker, lockFinalizer, NAMESPACE, deferredFinalizations);
  }

  /** Creates a mock record whose lock needs finalization and whose asset id is the given value. */
  private Result createRecord(String assetId, boolean needsFinalization) {
    Result record = mock(Result.class);
    when(stateChecker.needsFinalization(record)).thenReturn(needsFinalization);
    when(record.getText(AuditorInternalValues.ASSET_LOCK_TABLE_ID_COLUMN_NAME)).thenReturn(assetId);
    return record;
  }

  @Test
  void handle_lockRecoveredGiven_shouldFinalizeAndCount() throws Exception {
    // Arrange
    Result record = createRecord("asset1", true);
    when(lockFinalizer.execute(NAMESPACE, "asset1")).thenReturn(LockFinalizer.Result.FINALIZED);

    // Act
    handler.handle(record);

    // Assert
    verify(lockFinalizer).execute(NAMESPACE, "asset1");
    verify(deferredFinalizations, never()).append(anyString());
    assertThat(handler.getFinalizedCount()).isEqualTo(1);
  }

  @Test
  void handle_releasedLockGiven_shouldNotFinalizeOrCount() throws Exception {
    // Arrange
    Result record = createRecord("asset1", false);

    // Act
    handler.handle(record);

    // Assert
    verify(lockFinalizer, never()).execute(anyString(), anyString());
    verify(deferredFinalizations, never()).append(anyString());
    assertThat(handler.getFinalizedCount()).isZero();
  }

  @Test
  void handle_notRecoverableGiven_shouldDeferAssetIdAndNotCount() throws Exception {
    // Arrange — the lock is still active, so recovery cannot complete during the scan.
    Result record = createRecord("asset1", true);
    when(lockFinalizer.execute(NAMESPACE, "asset1")).thenReturn(LockFinalizer.Result.NOT_FINALIZED);

    // Act
    handler.handle(record);

    // Assert — the asset id is deferred for a post-scan retry, not counted as finalized.
    verify(deferredFinalizations).append("asset1");
    assertThat(handler.getFinalizedCount()).isZero();
  }

  @Test
  void handle_mixedRecordsGiven_shouldFinalizeReleasedLocksAndDeferActiveOnes() throws Exception {
    // Arrange
    Result recovered1 = createRecord("recovered1", true);
    Result recovered2 = createRecord("recovered2", true);
    Result active = createRecord("active", true);
    Result released = createRecord("released", false);
    when(lockFinalizer.execute(NAMESPACE, "recovered1")).thenReturn(LockFinalizer.Result.FINALIZED);
    when(lockFinalizer.execute(NAMESPACE, "recovered2")).thenReturn(LockFinalizer.Result.FINALIZED);
    when(lockFinalizer.execute(NAMESPACE, "active")).thenReturn(LockFinalizer.Result.NOT_FINALIZED);

    // Act
    handler.handle(recovered1);
    handler.handle(released);
    handler.handle(active);
    handler.handle(recovered2);

    // Assert
    verify(lockFinalizer, never()).execute(NAMESPACE, "released");
    verify(deferredFinalizations).append("active");
    verify(deferredFinalizations, never()).append("recovered1");
    verify(deferredFinalizations, never()).append("recovered2");
    assertThat(handler.getFinalizedCount()).isEqualTo(2);
  }

  @Test
  void handle_assetIdMissingGiven_shouldThrowExceptionWithoutFinalizing() {
    // Arrange — the record needs finalization but has no id column.
    Result record = createRecord(null, true);

    // Act & Assert — the RPC is never issued for a record without an asset id.
    assertThatThrownBy(() -> handler.handle(record))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(AuditorInternalValues.ASSET_LOCK_TABLE_ID_COLUMN_NAME);
    verify(lockFinalizer, never()).execute(anyString(), anyString());
    verify(deferredFinalizations, never()).append(anyString());
  }

  @Test
  void handle_finalizeFailureGiven_shouldPropagateExceptionAndNotCount() {
    // Arrange
    Result record = createRecord("asset1", true);
    when(lockFinalizer.execute(NAMESPACE, "asset1"))
        .thenThrow(new RuntimeException("recovery failed"));

    // Act & Assert
    assertThatThrownBy(() -> handler.handle(record))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("recovery failed");
    assertThat(handler.getFinalizedCount()).isZero();
  }
}
