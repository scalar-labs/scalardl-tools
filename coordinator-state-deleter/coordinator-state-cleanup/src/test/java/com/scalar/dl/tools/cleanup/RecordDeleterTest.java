package com.scalar.dl.tools.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.scalar.db.api.Delete;
import com.scalar.db.api.DistributedStorage;
import com.scalar.db.api.Result;
import com.scalar.db.exception.storage.ExecutionException;
import com.scalar.db.io.Key;
import com.scalar.db.transaction.consensuscommit.Coordinator;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RecordDeleterTest {

  private DistributedStorage storage;

  @BeforeEach
  void setUp() {
    storage = mock(DistributedStorage.class);
  }

  @SuppressWarnings("deprecation")
  private Result createScanResult(String pkValue) {
    Result result = mock(Result.class);
    when(result.getPartitionKey()).thenReturn(Optional.of(Key.ofText("tx_id", pkValue)));
    return result;
  }

  @Test
  void submitAndAwaitCompletion_shouldDeleteAllSuccessfully() throws Exception {
    // Arrange
    doNothing().when(storage).delete(any(Delete.class));

    try (RecordDeleter deleter = new RecordDeleter(storage, 2)) {
      // Act
      deleter.submit(createScanResult("tx1"));
      deleter.submit(createScanResult("tx2"));
      deleter.submit(createScanResult("tx3"));
      deleter.awaitCompletion();

      // Assert
      verify(storage, times(3)).delete(any(Delete.class));
      assertThat(deleter.getDeletedCount()).isEqualTo(3);
    }
  }

  @Test
  void submitAndAwaitCompletion_workerFailureGiven_shouldPropagateException() throws Exception {
    // Arrange
    doThrow(new ExecutionException("DB unavailable")).when(storage).delete(any(Delete.class));

    try (RecordDeleter deleter = new RecordDeleter(storage, 1)) {
      // Act
      deleter.submit(createScanResult("tx1"));

      // Assert
      assertThatThrownBy(deleter::awaitCompletion)
          .isInstanceOf(ExecutionException.class)
          .hasMessageContaining("DB unavailable");
    }
  }

  @Test
  void submitAndAwaitCompletion_multipleWorkerFailuresGiven_shouldAddSuppressedExceptions()
      throws Exception {
    // Arrange
    doThrow(new ExecutionException("error-1"))
        .doThrow(new ExecutionException("error-2"))
        .when(storage)
        .delete(any(Delete.class));

    try (RecordDeleter deleter = new RecordDeleter(storage, 2)) {
      // Act
      deleter.submit(createScanResult("tx1"));
      deleter.submit(createScanResult("tx2"));

      // Assert
      assertThatThrownBy(deleter::awaitCompletion)
          .isInstanceOf(ExecutionException.class)
          .satisfies(
              e -> {
                assertThat(e.getSuppressed()).hasSize(1);
                assertThat(e.getSuppressed()[0]).isInstanceOf(ExecutionException.class);
              });
    }
  }

  @Test
  @SuppressWarnings("deprecation")
  void submit_shouldConstructDeleteWithCorrectPartitionKey() throws Exception {
    // Arrange
    doNothing().when(storage).delete(any(Delete.class));

    Result scanResult = mock(Result.class);
    when(scanResult.getPartitionKey()).thenReturn(Optional.of(Key.ofText("tx_id", "tx-abc")));

    try (RecordDeleter deleter = new RecordDeleter(storage, 1)) {
      // Act
      deleter.submit(scanResult);
      deleter.awaitCompletion();

      // Assert
      ArgumentCaptor<Delete> captor = ArgumentCaptor.forClass(Delete.class);
      verify(storage).delete(captor.capture());
      Delete captured = captor.getValue();
      assertThat(captured.forNamespace()).hasValue(Coordinator.NAMESPACE);
      assertThat(captured.forTable()).hasValue(Coordinator.TABLE);
      assertThat((Object) captured.getPartitionKey()).isEqualTo(Key.ofText("tx_id", "tx-abc"));
    }
  }

  @Test
  void awaitCompletion_noSubmissionsGiven_shouldNotThrow() {
    // Arrange
    try (RecordDeleter deleter = new RecordDeleter(storage, 2)) {
      // Act & Assert
      assertThatCode(deleter::awaitCompletion).doesNotThrowAnyException();
      assertThat(deleter.getDeletedCount()).isEqualTo(0);
    }
  }

  @Test
  void close_inFlightTasksGiven_shouldInterruptWorkersAndReturnWithoutException() throws Exception {
    // Arrange
    // Make delete() block so the worker stays in-flight
    CountDownLatch blocked = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              blocked.await(); // block until interrupted by shutdownNow()
              return null;
            })
        .when(storage)
        .delete(any(Delete.class));

    RecordDeleter deleter = new RecordDeleter(storage, 1);
    deleter.submit(createScanResult("tx1"));

    // Act & Assert
    assertThatCode(deleter::close).doesNotThrowAnyException();
  }

  @Test
  void submit_concurrentCallersGiven_shouldProcessAllRecords() throws Exception {
    // Arrange
    int totalRecords = 200;
    int callerThreads = 8;
    doNothing().when(storage).delete(any(Delete.class));

    try (RecordDeleter deleter = new RecordDeleter(storage, 4)) {
      // Act
      ExecutorService callers = Executors.newFixedThreadPool(callerThreads);
      CountDownLatch startLatch = new CountDownLatch(1);
      List<Future<?>> callerFutures = new ArrayList<>();

      for (int i = 0; i < totalRecords; i++) {
        int idx = i;
        callerFutures.add(
            callers.submit(
                () -> {
                  startLatch.await();
                  deleter.submit(createScanResult("tx-" + idx));
                  return null;
                }));
      }
      startLatch.countDown();

      for (Future<?> f : callerFutures) {
        f.get(10, TimeUnit.SECONDS);
      }
      deleter.awaitCompletion();
      callers.shutdown();

      // Assert
      verify(storage, times(totalRecords)).delete(any(Delete.class));
      assertThat(deleter.getDeletedCount()).isEqualTo(totalRecords);
    }
  }
}
