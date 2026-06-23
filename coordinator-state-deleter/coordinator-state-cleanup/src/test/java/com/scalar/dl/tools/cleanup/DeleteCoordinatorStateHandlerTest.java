package com.scalar.dl.tools.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.scalar.db.api.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DeleteCoordinatorStateHandlerTest {

  private RecordDeletionChecker deletionChecker;
  private RecordDeleter recordDeleter;
  private DeleteCoordinatorStateHandler handler;

  @BeforeEach
  void setUp() {
    deletionChecker = mock(RecordDeletionChecker.class);
    recordDeleter = mock(RecordDeleter.class);
    handler = new DeleteCoordinatorStateHandler(deletionChecker, recordDeleter);
  }

  @Test
  void handle_deletableRecordGiven_shouldDeleteAndCount() throws Exception {
    // Arrange
    Result record = mock(Result.class);
    when(deletionChecker.isDeletable(record)).thenReturn(true);

    // Act
    handler.handle(record);

    // Assert
    verify(recordDeleter).execute(record);
    assertThat(handler.getDeletedCount()).isEqualTo(1);
  }

  @Test
  void handle_nonDeletableRecordGiven_shouldNotDeleteOrCount() throws Exception {
    // Arrange
    Result record = mock(Result.class);
    when(deletionChecker.isDeletable(record)).thenReturn(false);

    // Act
    handler.handle(record);

    // Assert
    verify(recordDeleter, never()).execute(any());
    assertThat(handler.getDeletedCount()).isZero();
  }

  @Test
  void handle_mixedRecordsGiven_shouldDeleteAndCountOnlyDeletableOnes() throws Exception {
    // Arrange
    Result deletable1 = mock(Result.class);
    Result deletable2 = mock(Result.class);
    Result notDeletable = mock(Result.class);
    when(deletionChecker.isDeletable(deletable1)).thenReturn(true);
    when(deletionChecker.isDeletable(deletable2)).thenReturn(true);
    when(deletionChecker.isDeletable(notDeletable)).thenReturn(false);

    // Act
    handler.handle(deletable1);
    handler.handle(notDeletable);
    handler.handle(deletable2);

    // Assert
    verify(recordDeleter).execute(deletable1);
    verify(recordDeleter).execute(deletable2);
    verify(recordDeleter, never()).execute(notDeletable);
    assertThat(handler.getDeletedCount()).isEqualTo(2);
  }

  @Test
  void handle_deleteFailureGiven_shouldPropagateExceptionAndNotCount() throws Exception {
    // Arrange
    Result record = mock(Result.class);
    when(deletionChecker.isDeletable(record)).thenReturn(true);
    doThrow(new RuntimeException("DB unavailable")).when(recordDeleter).execute(record);

    // Act & Assert
    assertThatThrownBy(() -> handler.handle(record))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("DB unavailable");
    assertThat(handler.getDeletedCount()).isZero();
  }
}
