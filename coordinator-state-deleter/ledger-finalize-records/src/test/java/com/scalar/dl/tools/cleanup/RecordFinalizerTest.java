package com.scalar.dl.tools.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.scalar.db.api.DistributedStorage;
import com.scalar.db.api.DistributedTransaction;
import com.scalar.db.api.DistributedTransactionManager;
import com.scalar.db.api.Get;
import com.scalar.db.api.Result;
import com.scalar.db.io.Key;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RecordFinalizerTest {

  private DistributedTransactionManager manager;
  private DistributedStorage storage;
  private RecordStateChecker stateChecker;

  @BeforeEach
  void setUp() {
    manager = mock(DistributedTransactionManager.class);
    storage = mock(DistributedStorage.class);
    stateChecker = mock(RecordStateChecker.class);
  }

  @SuppressWarnings("deprecation")
  private Result createScanResult(String pkValue) {
    Result result = mock(Result.class);
    when(result.getPartitionKey()).thenReturn(Optional.of(Key.ofText("id", pkValue)));
    when(result.getClusteringKey()).thenReturn(Optional.empty());
    return result;
  }

  @Test
  void execute_shouldFinalizeAllSuccessfully() throws Exception {
    // Arrange
    DistributedTransaction tx = mock(DistributedTransaction.class);
    when(manager.begin()).thenReturn(tx);
    when(storage.get(any(Get.class))).thenReturn(Optional.empty());

    RecordFinalizer finalizer = new RecordFinalizer(manager, storage, stateChecker);

    // Act
    finalizer.execute("ns", "tbl", createScanResult("pk1"));
    finalizer.execute("ns", "tbl", createScanResult("pk2"));
    finalizer.execute("ns", "tbl", createScanResult("pk3"));

    // Assert
    verify(tx, times(3)).get(any(Get.class));
    verify(storage, times(3)).get(any(Get.class));
    assertThat(finalizer.getFinalizedCount()).isEqualTo(3);
  }

  @Test
  void execute_recoveryCompletesAfterRetries_shouldFinalize() throws Exception {
    // Arrange
    DistributedTransaction tx = mock(DistributedTransaction.class);
    when(manager.begin()).thenReturn(tx);

    Result nonTerminalResult = mock(Result.class);
    when(stateChecker.needsFinalization(nonTerminalResult)).thenReturn(true, true, false);
    when(storage.get(any(Get.class)))
        .thenReturn(Optional.of(nonTerminalResult))
        .thenReturn(Optional.of(nonTerminalResult))
        .thenReturn(Optional.of(nonTerminalResult));

    RecordFinalizer finalizer = new RecordFinalizer(manager, storage, stateChecker);

    // Act
    finalizer.execute("ns", "tbl", createScanResult("pk1"));

    // Assert
    verify(tx, times(3)).get(any(Get.class));
    verify(storage, times(3)).get(any(Get.class));
    assertThat(finalizer.getFinalizedCount()).isEqualTo(1);
  }

  @Test
  void execute_recoveryNeverCompletes_shouldThrowException() throws Exception {
    // Arrange
    DistributedTransaction tx = mock(DistributedTransaction.class);
    when(manager.begin()).thenReturn(tx);

    Result nonTerminalResult = mock(Result.class);
    when(stateChecker.needsFinalization(nonTerminalResult)).thenReturn(true);
    when(storage.get(any(Get.class))).thenReturn(Optional.of(nonTerminalResult));

    RecordFinalizer finalizer = new RecordFinalizer(manager, storage, stateChecker);

    // Act & Assert
    assertThatThrownBy(() -> finalizer.execute("ns", "tbl", createScanResult("pk1")))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("not finalized");
  }

  @Test
  void execute_dbFailureGiven_shouldPropagateException() throws Exception {
    // Arrange
    when(manager.begin()).thenThrow(new RuntimeException("DB unavailable"));

    RecordFinalizer finalizer = new RecordFinalizer(manager, storage, stateChecker);

    // Act & Assert
    assertThatThrownBy(() -> finalizer.execute("ns", "tbl", createScanResult("pk1")))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("DB unavailable");
  }

  @Test
  @SuppressWarnings("deprecation")
  void execute_noClusteringKeyGiven_shouldConstructCorrectGet() throws Exception {
    // Arrange
    DistributedTransaction tx = mock(DistributedTransaction.class);
    when(manager.begin()).thenReturn(tx);
    when(storage.get(any(Get.class))).thenReturn(Optional.empty());

    Result scanResult = mock(Result.class);
    when(scanResult.getPartitionKey()).thenReturn(Optional.of(Key.ofText("id", "pk1")));
    when(scanResult.getClusteringKey()).thenReturn(Optional.empty());

    RecordFinalizer finalizer = new RecordFinalizer(manager, storage, stateChecker);

    // Act
    finalizer.execute("ns", "tbl", scanResult);

    // Assert
    ArgumentCaptor<Get> captor = ArgumentCaptor.forClass(Get.class);
    verify(tx).get(captor.capture());
    Get captured = captor.getValue();
    assertThat(captured.forNamespace()).hasValue("ns");
    assertThat(captured.forTable()).hasValue("tbl");
    assertThat((Object) captured.getPartitionKey()).isEqualTo(Key.ofText("id", "pk1"));
    assertThat(captured.getClusteringKey()).isEmpty();
  }
}
