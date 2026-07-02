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
import com.scalar.dl.tools.common.AuditorInternalValues;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RequestProofDeleterTest {

  private static final String NAMESPACE = "auditor";
  private static final String NONCE = "nonce";

  private DistributedStorage storage;

  @BeforeEach
  void setUp() {
    storage = mock(DistributedStorage.class);
  }

  private Result createScanResult(String nonceValue) {
    Result result = mock(Result.class);
    when(result.getText(NONCE)).thenReturn(nonceValue);
    return result;
  }

  @Test
  void execute_shouldDeleteAllSuccessfully() throws Exception {
    // Arrange
    doNothing().when(storage).delete(any(Delete.class));
    RequestProofDeleter deleter = new RequestProofDeleter(storage, NAMESPACE);

    // Act
    deleter.execute(createScanResult("nonce1"));
    deleter.execute(createScanResult("nonce2"));
    deleter.execute(createScanResult("nonce3"));

    // Assert
    verify(storage, times(3)).delete(any(Delete.class));
  }

  @Test
  void execute_partitionKeyMissingGiven_shouldThrowIllegalArgumentException() {
    // Arrange: a record whose partition key column (nonce) is null.
    Result result = mock(Result.class);
    when(result.getText(NONCE)).thenReturn(null);
    RequestProofDeleter deleter = new RequestProofDeleter(storage, NAMESPACE);

    // Act & Assert
    assertThatThrownBy(() -> deleter.execute(result))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("nonce");
  }

  @Test
  void execute_dbFailureGiven_shouldPropagateException() throws Exception {
    // Arrange
    doThrow(new ExecutionException("DB unavailable")).when(storage).delete(any(Delete.class));
    RequestProofDeleter deleter = new RequestProofDeleter(storage, NAMESPACE);

    // Act & Assert
    assertThatThrownBy(() -> deleter.execute(createScanResult("nonce1")))
        .isInstanceOf(ExecutionException.class)
        .hasMessageContaining("DB unavailable");
  }

  @Test
  void execute_shouldConstructDeleteWithConfiguredNamespaceAndPartitionKey() throws Exception {
    // Arrange
    String namespace = "my_auditor";
    doNothing().when(storage).delete(any(Delete.class));

    Result scanResult = mock(Result.class);
    when(scanResult.getText(NONCE)).thenReturn("nonce-abc");

    RequestProofDeleter deleter = new RequestProofDeleter(storage, namespace);

    // Act
    deleter.execute(scanResult);

    // Assert
    ArgumentCaptor<Delete> captor = ArgumentCaptor.forClass(Delete.class);
    verify(storage).delete(captor.capture());
    Delete captured = captor.getValue();
    assertThat(captured.forNamespace()).hasValue(namespace);
    assertThat(captured.forTable()).hasValue(AuditorInternalValues.REQUEST_PROOF_TABLE_NAME);
    assertThat((Object) captured.getPartitionKey()).isEqualTo(Key.ofText(NONCE, "nonce-abc"));
  }
}
