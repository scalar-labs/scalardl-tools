package com.scalar.dl.tools.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.scalar.db.api.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FinalizeAssetLockHandlerTest {

  private static final String NAMESPACE = "default";

  private LockStateChecker stateChecker;
  private LockFinalizer lockFinalizer;
  private FinalizeAssetLockHandler handler;

  @BeforeEach
  void setUp() {
    stateChecker = mock(LockStateChecker.class);
    lockFinalizer = mock(LockFinalizer.class);
    handler = new FinalizeAssetLockHandler(stateChecker, lockFinalizer, NAMESPACE);
  }

  @Test
  void handle_lockNeedingFinalizationGiven_shouldFinalizeAndCount() throws Exception {
    // Arrange
    Result record = mock(Result.class);
    when(stateChecker.needsFinalization(record)).thenReturn(true);

    // Act
    handler.handle(record);

    // Assert
    verify(lockFinalizer).execute(NAMESPACE, record);
    assertThat(handler.getFinalizedCount()).isEqualTo(1);
  }

  @Test
  void handle_releasedLockGiven_shouldNotFinalizeOrCount() throws Exception {
    // Arrange
    Result record = mock(Result.class);
    when(stateChecker.needsFinalization(record)).thenReturn(false);

    // Act
    handler.handle(record);

    // Assert
    verify(lockFinalizer, never()).execute(anyString(), any());
    assertThat(handler.getFinalizedCount()).isZero();
  }

  @Test
  void handle_mixedRecordsGiven_shouldFinalizeAndCountOnlyLocksNeedingFinalization()
      throws Exception {
    // Arrange
    Result needsFinalization1 = mock(Result.class);
    Result needsFinalization2 = mock(Result.class);
    Result released = mock(Result.class);
    when(stateChecker.needsFinalization(needsFinalization1)).thenReturn(true);
    when(stateChecker.needsFinalization(needsFinalization2)).thenReturn(true);
    when(stateChecker.needsFinalization(released)).thenReturn(false);

    // Act
    handler.handle(needsFinalization1);
    handler.handle(released);
    handler.handle(needsFinalization2);

    // Assert
    verify(lockFinalizer).execute(NAMESPACE, needsFinalization1);
    verify(lockFinalizer).execute(NAMESPACE, needsFinalization2);
    verify(lockFinalizer, never()).execute(NAMESPACE, released);
    assertThat(handler.getFinalizedCount()).isEqualTo(2);
  }

  @Test
  void handle_finalizeFailureGiven_shouldPropagateExceptionAndNotCount() {
    // Arrange
    Result record = mock(Result.class);
    when(stateChecker.needsFinalization(record)).thenReturn(true);
    doThrow(new RuntimeException("recovery failed")).when(lockFinalizer).execute(NAMESPACE, record);

    // Act & Assert
    assertThatThrownBy(() -> handler.handle(record))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("recovery failed");
    assertThat(handler.getFinalizedCount()).isZero();
  }
}
