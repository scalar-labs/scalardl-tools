package com.scalar.dl.tools.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.scalar.db.api.DistributedTransaction;
import com.scalar.db.api.DistributedTransactionManager;
import com.scalar.db.api.Get;
import com.scalar.db.api.Result;
import com.scalar.db.io.Key;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RecordFinalizerTest {

  private DistributedTransactionManager manager;

  @BeforeEach
  void setUp() {
    manager = mock(DistributedTransactionManager.class);
  }

  @SuppressWarnings("deprecation")
  private Result createScanResult(String pkValue) {
    Result result = mock(Result.class);
    when(result.getPartitionKey()).thenReturn(Optional.of(Key.ofText("id", pkValue)));
    when(result.getClusteringKey()).thenReturn(Optional.empty());
    return result;
  }

  @Test
  void submitAndAwaitCompletion_shouldFinalizeAllSuccessfully() throws Exception {
    // Arrange
    DistributedTransaction tx = mock(DistributedTransaction.class);
    when(manager.begin()).thenReturn(tx);
    when(tx.get(any(Get.class))).thenReturn(Optional.empty());

    try (RecordFinalizer dispatcher = new RecordFinalizer(manager, 2)) {
      // Act
      dispatcher.submit("ns", "tbl", createScanResult("pk1"));
      dispatcher.submit("ns", "tbl", createScanResult("pk2"));
      dispatcher.submit("ns", "tbl", createScanResult("pk3"));
      dispatcher.awaitCompletion();

      // Assert
      assertThat(dispatcher.getFinalizedCount()).isEqualTo(3);
    }
  }

  @Test
  void submitAndAwaitCompletion_workerFailureGiven_shouldPropagateException() throws Exception {
    // Arrange
    when(manager.begin()).thenThrow(new RuntimeException("DB unavailable"));

    try (RecordFinalizer dispatcher = new RecordFinalizer(manager, 1)) {
      // Act
      dispatcher.submit("ns", "tbl", createScanResult("pk1"));

      // Assert
      assertThatThrownBy(dispatcher::awaitCompletion)
          .isInstanceOf(RuntimeException.class)
          .hasMessageContaining("DB unavailable");
    }
  }

  @Test
  void submitAndAwaitCompletion_multipleWorkerFailuresGiven_shouldAccumulateSuppressedExceptions()
      throws Exception {
    // Arrange
    // Use thenAnswer to create a new exception instance per call; thenThrow reuses the same
    // instance, which causes self-suppression to be silently ignored by addSuppressed.
    when(manager.begin())
        .thenAnswer(
            invocation -> {
              throw new RuntimeException("DB unavailable");
            });

    try (RecordFinalizer dispatcher = new RecordFinalizer(manager, 2)) {
      // Act
      dispatcher.submit("ns", "tbl", createScanResult("pk1"));
      dispatcher.submit("ns", "tbl", createScanResult("pk2"));

      // Assert
      assertThatThrownBy(dispatcher::awaitCompletion)
          .isInstanceOf(RuntimeException.class)
          .satisfies(
              e -> {
                assertThat(e.getSuppressed()).hasSize(1);
                assertThat(e.getSuppressed()[0]).isInstanceOf(RuntimeException.class);
              });
    }
  }

  @Test
  @SuppressWarnings("deprecation")
  void submit_noClusteringKeyGiven_shouldExecuteGetWithoutClusteringKey() throws Exception {
    // Arrange
    DistributedTransaction tx = mock(DistributedTransaction.class);
    when(manager.begin()).thenReturn(tx);
    when(tx.get(any(Get.class))).thenReturn(Optional.empty());

    Result scanResult = mock(Result.class);
    when(scanResult.getPartitionKey()).thenReturn(Optional.of(Key.ofText("id", "pk1")));
    when(scanResult.getClusteringKey()).thenReturn(Optional.empty());

    try (RecordFinalizer dispatcher = new RecordFinalizer(manager, 1)) {
      // Act
      dispatcher.submit("ns", "tbl", scanResult);
      dispatcher.awaitCompletion();

      // Assert
      ArgumentCaptor<Get> captor = ArgumentCaptor.forClass(Get.class);
      verify(tx).get(captor.capture());
      assertThat(captor.getValue().getClusteringKey()).isEmpty();
    }
  }

  @Test
  void awaitCompletion_noSubmissionsGiven_shouldNotThrow() {
    // Arrange
    try (RecordFinalizer dispatcher = new RecordFinalizer(manager, 2)) {
      // Act & Assert
      assertThatCode(dispatcher::awaitCompletion).doesNotThrowAnyException();
      assertThat(dispatcher.getFinalizedCount()).isEqualTo(0);
    }
  }

  @Test
  void close_inFlightTasksGiven_shouldInterruptWorkersAndReturnWithoutException() throws Exception {
    // Arrange
    // Make begin() block so the worker stays in-flight
    CountDownLatch blocked = new CountDownLatch(1);
    when(manager.begin())
        .thenAnswer(
            invocation -> {
              blocked.await(); // block until interrupted by shutdownNow()
              return null;
            });

    RecordFinalizer dispatcher = new RecordFinalizer(manager, 1);
    dispatcher.submit("ns", "tbl", createScanResult("pk1"));

    // Act & Assert
    assertThatCode(dispatcher::close).doesNotThrowAnyException();
  }
}
