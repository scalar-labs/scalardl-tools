package com.scalar.dl.tools.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import java.util.Optional;
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
  void execute_shouldDeleteAllSuccessfully() throws Exception {
    // Arrange
    doNothing().when(storage).delete(any(Delete.class));
    RecordDeleter deleter = new RecordDeleter(storage, Coordinator.NAMESPACE);

    // Act
    deleter.execute(createScanResult("tx1"));
    deleter.execute(createScanResult("tx2"));
    deleter.execute(createScanResult("tx3"));

    // Assert
    verify(storage, times(3)).delete(any(Delete.class));
  }

  @Test
  void execute_dbFailureGiven_shouldPropagateException() throws Exception {
    // Arrange
    doThrow(new ExecutionException("DB unavailable")).when(storage).delete(any(Delete.class));
    RecordDeleter deleter = new RecordDeleter(storage, Coordinator.NAMESPACE);

    // Act & Assert
    assertThatThrownBy(() -> deleter.execute(createScanResult("tx1")))
        .isInstanceOf(ExecutionException.class)
        .hasMessageContaining("DB unavailable");
  }

  @Test
  @SuppressWarnings("deprecation")
  void execute_shouldConstructDeleteWithConfiguredNamespaceAndPartitionKey() throws Exception {
    // Arrange
    String namespace = "my_coordinator";
    doNothing().when(storage).delete(any(Delete.class));

    Result scanResult = mock(Result.class);
    when(scanResult.getPartitionKey()).thenReturn(Optional.of(Key.ofText("tx_id", "tx-abc")));

    RecordDeleter deleter = new RecordDeleter(storage, namespace);

    // Act
    deleter.execute(scanResult);

    // Assert
    ArgumentCaptor<Delete> captor = ArgumentCaptor.forClass(Delete.class);
    verify(storage).delete(captor.capture());
    Delete captured = captor.getValue();
    assertThat(captured.forNamespace()).hasValue(namespace);
    assertThat(captured.forTable()).hasValue(Coordinator.TABLE);
    assertThat((Object) captured.getPartitionKey()).isEqualTo(Key.ofText("tx_id", "tx-abc"));
  }
}
