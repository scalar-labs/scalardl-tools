package com.scalar.dl.tools.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.scalar.db.api.Result;
import com.scalar.db.api.TransactionState;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VerifyTransactionRecordHandlerTest {

  private VerifyTransactionRecordHandler handler;

  @BeforeEach
  void setUp() {
    handler = new VerifyTransactionRecordHandler("ns1", "tbl1");
  }

  @Test
  void handle_terminalRecordsGiven_shouldNotThrowOrDetect() {
    // Act & Assert
    assertThatCode(
            () -> {
              handler.handle(VerifyTestRecords.committed());
              handler.handle(VerifyTestRecords.record(TransactionState.ABORTED, 500L));
            })
        .doesNotThrowAnyException();
    assertThat(handler.isDetected()).isFalse();
  }

  @Test
  void handle_preparedRecordGiven_shouldThrowAndDetect() {
    // Act & Assert
    assertThatThrownBy(() -> handler.handle(VerifyTestRecords.prepared()))
        .isInstanceOf(Exception.class);
    assertThat(handler.isDetected()).isTrue();
  }

  @Test
  void handle_deletedRecordGiven_shouldThrowAndDetect() {
    // Act & Assert
    assertThatThrownBy(
            () -> handler.handle(VerifyTestRecords.record(TransactionState.DELETED, 500L)))
        .isInstanceOf(Exception.class);
    assertThat(handler.isDetected()).isTrue();
  }

  @Test
  void handle_recordPreparedInFutureGiven_shouldThrowAndDetect() {
    // Arrange
    long future = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1);

    // Act & Assert
    assertThatThrownBy(
            () -> handler.handle(VerifyTestRecords.record(TransactionState.PREPARED, future)))
        .isInstanceOf(Exception.class);
    assertThat(handler.isDetected()).isTrue();
  }

  @Test
  void handle_terminalRecordsAfterDetectionGiven_shouldThrowDistinctExceptionOnEachCall() {
    // Arrange
    Throwable onDetection = catchThrowable(() -> handler.handle(VerifyTestRecords.prepared()));
    Result terminal = VerifyTestRecords.committed();

    // Act
    Throwable first = catchThrowable(() -> handler.handle(terminal));
    Throwable second = catchThrowable(() -> handler.handle(terminal));

    // Assert
    assertThat(onDetection).isInstanceOf(Exception.class);
    assertThat(first).isInstanceOf(Exception.class).isNotSameAs(onDetection);
    assertThat(second).isInstanceOf(Exception.class).isNotSameAs(onDetection).isNotSameAs(first);
  }

  @Test
  void handle_concurrentCallsAfterDetectionGiven_shouldMakeEveryThreadExitByException()
      throws Exception {
    // Arrange
    // Each worker handles terminal records endlessly, so it can exit only by an exception.
    int workers = 4;
    Result terminal = VerifyTestRecords.committed();
    CountDownLatch started = new CountDownLatch(workers);
    ExecutorService executor = Executors.newFixedThreadPool(workers);
    try {
      List<Future<Void>> futures = new ArrayList<>();
      for (int i = 0; i < workers; i++) {
        futures.add(
            executor.submit(
                () -> {
                  started.countDown();
                  while (true) {
                    handler.handle(terminal);
                  }
                }));
      }
      assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();

      // Act
      Throwable onDetection = catchThrowable(() -> handler.handle(VerifyTestRecords.prepared()));

      // Assert
      List<Throwable> thrown = new ArrayList<>();
      thrown.add(onDetection);
      for (Future<Void> future : futures) {
        Throwable failure = catchThrowable(() -> future.get(10, TimeUnit.SECONDS));
        assertThat(failure).isInstanceOf(ExecutionException.class);
        thrown.add(failure.getCause());
      }
      // The scanner adds the exceptions of the other workers to the first one as suppressed, which
      // fails if two workers throw the same instance.
      assertThat(thrown).doesNotContainNull().doesNotHaveDuplicates();
    } finally {
      executor.shutdownNow();
    }
  }
}
