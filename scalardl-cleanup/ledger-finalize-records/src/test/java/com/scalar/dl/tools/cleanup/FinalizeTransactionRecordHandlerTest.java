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

class FinalizeTransactionRecordHandlerTest {

  private static final String NAMESPACE = "ns1";
  private static final String TABLE = "tbl1";

  private RecordStateChecker stateChecker;
  private RecordFinalizer recordFinalizer;
  private FinalizeTransactionRecordHandler handler;

  @BeforeEach
  void setUp() {
    stateChecker = mock(RecordStateChecker.class);
    recordFinalizer = mock(RecordFinalizer.class);
    handler = new FinalizeTransactionRecordHandler(stateChecker, recordFinalizer, NAMESPACE, TABLE);
  }

  @Test
  void handle_recordNeedingFinalizationGiven_shouldFinalizeAndCount() throws Exception {
    // Arrange
    Result record = mock(Result.class);
    when(stateChecker.needsFinalization(record)).thenReturn(true);
    when(recordFinalizer.execute(NAMESPACE, TABLE, record)).thenReturn(true);

    // Act
    handler.handle(record);

    // Assert
    verify(recordFinalizer).execute(NAMESPACE, TABLE, record);
    assertThat(handler.getFinalizedCount()).isEqualTo(1);
  }

  @Test
  void handle_recordNotYetRecoverableGiven_shouldNotCount() throws Exception {
    // Arrange: the record needs finalization but recoverRecord reports it as not yet recoverable
    // (execute returns false), so it must not be counted as finalized.
    Result record = mock(Result.class);
    when(stateChecker.needsFinalization(record)).thenReturn(true);
    when(recordFinalizer.execute(NAMESPACE, TABLE, record)).thenReturn(false);

    // Act
    handler.handle(record);

    // Assert
    verify(recordFinalizer).execute(NAMESPACE, TABLE, record);
    assertThat(handler.getFinalizedCount()).isZero();
  }

  @Test
  void handle_terminalRecordGiven_shouldNotFinalizeOrCount() throws Exception {
    // Arrange
    Result record = mock(Result.class);
    when(stateChecker.needsFinalization(record)).thenReturn(false);

    // Act
    handler.handle(record);

    // Assert
    verify(recordFinalizer, never()).execute(anyString(), anyString(), any());
    assertThat(handler.getFinalizedCount()).isZero();
  }

  @Test
  void handle_mixedRecordsGiven_shouldFinalizeAndCountOnlyNonTerminalOnes() throws Exception {
    // Arrange
    Result needsFinalization1 = mock(Result.class);
    Result needsFinalization2 = mock(Result.class);
    Result terminal = mock(Result.class);
    when(stateChecker.needsFinalization(needsFinalization1)).thenReturn(true);
    when(stateChecker.needsFinalization(needsFinalization2)).thenReturn(true);
    when(stateChecker.needsFinalization(terminal)).thenReturn(false);
    when(recordFinalizer.execute(NAMESPACE, TABLE, needsFinalization1)).thenReturn(true);
    when(recordFinalizer.execute(NAMESPACE, TABLE, needsFinalization2)).thenReturn(true);

    // Act
    handler.handle(needsFinalization1);
    handler.handle(terminal);
    handler.handle(needsFinalization2);

    // Assert
    verify(recordFinalizer).execute(NAMESPACE, TABLE, needsFinalization1);
    verify(recordFinalizer).execute(NAMESPACE, TABLE, needsFinalization2);
    verify(recordFinalizer, never()).execute(NAMESPACE, TABLE, terminal);
    assertThat(handler.getFinalizedCount()).isEqualTo(2);
  }

  @Test
  void handle_finalizeFailureGiven_shouldPropagateExceptionAndNotCount() throws Exception {
    // Arrange
    Result record = mock(Result.class);
    when(stateChecker.needsFinalization(record)).thenReturn(true);
    doThrow(new RuntimeException("recovery failed"))
        .when(recordFinalizer)
        .execute(NAMESPACE, TABLE, record);

    // Act & Assert
    assertThatThrownBy(() -> handler.handle(record))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("recovery failed");
    assertThat(handler.getFinalizedCount()).isZero();
  }
}
