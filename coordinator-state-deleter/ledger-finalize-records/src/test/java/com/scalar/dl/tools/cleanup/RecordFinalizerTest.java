package com.scalar.dl.tools.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.scalar.db.api.DistributedTransactionManager;
import com.scalar.db.api.Result;
import com.scalar.db.io.Key;
import java.util.Optional;
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
  void execute_shouldCallRecoverRecordOncePerRecord() throws Exception {
    // Arrange
    when(manager.recoverRecord(eq("ns"), eq("tbl"), any(Key.class), nullable(Key.class)))
        .thenReturn(true);

    RecordFinalizer finalizer = new RecordFinalizer(manager);

    // Act
    boolean recovered = finalizer.execute("ns", "tbl", createScanResult("pk1"));
    finalizer.execute("ns", "tbl", createScanResult("pk2"));
    finalizer.execute("ns", "tbl", createScanResult("pk3"));

    // Assert
    assertThat(recovered).isTrue();
    verify(manager, times(3))
        .recoverRecord(eq("ns"), eq("tbl"), any(Key.class), nullable(Key.class));
  }

  @Test
  void execute_recoverRecordReturnsFalse_shouldNotRetryOrThrow() throws Exception {
    // Arrange: recoverRecord returns false (record outside the finalization window). The finalizer
    // must treat it as out of scope: call recoverRecord exactly once and not throw.
    when(manager.recoverRecord(eq("ns"), eq("tbl"), any(Key.class), nullable(Key.class)))
        .thenReturn(false);

    RecordFinalizer finalizer = new RecordFinalizer(manager);

    // Act
    boolean recovered = finalizer.execute("ns", "tbl", createScanResult("pk1"));

    // Assert
    assertThat(recovered).isFalse();
    verify(manager, times(1))
        .recoverRecord(eq("ns"), eq("tbl"), any(Key.class), nullable(Key.class));
  }

  @Test
  void execute_recoverRecordThrows_shouldPropagateException() throws Exception {
    // Arrange
    when(manager.recoverRecord(eq("ns"), eq("tbl"), any(Key.class), nullable(Key.class)))
        .thenThrow(new RuntimeException("DB unavailable"));

    RecordFinalizer finalizer = new RecordFinalizer(manager);

    // Act & Assert
    assertThatThrownBy(() -> finalizer.execute("ns", "tbl", createScanResult("pk1")))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("DB unavailable");
  }

  @Test
  @SuppressWarnings("deprecation")
  void execute_noClusteringKeyGiven_shouldCallRecoverRecordWithNullClusteringKey()
      throws Exception {
    // Arrange
    when(manager.recoverRecord(eq("ns"), eq("tbl"), any(Key.class), nullable(Key.class)))
        .thenReturn(true);

    Result scanResult = mock(Result.class);
    when(scanResult.getPartitionKey()).thenReturn(Optional.of(Key.ofText("id", "pk1")));
    when(scanResult.getClusteringKey()).thenReturn(Optional.empty());

    RecordFinalizer finalizer = new RecordFinalizer(manager);

    // Act
    finalizer.execute("ns", "tbl", scanResult);

    // Assert
    ArgumentCaptor<Key> pkCaptor = ArgumentCaptor.forClass(Key.class);
    ArgumentCaptor<Key> ckCaptor = ArgumentCaptor.forClass(Key.class);
    verify(manager).recoverRecord(eq("ns"), eq("tbl"), pkCaptor.capture(), ckCaptor.capture());
    assertThat((Object) pkCaptor.getValue()).isEqualTo(Key.ofText("id", "pk1"));
    assertThat((Object) ckCaptor.getValue()).isNull();
  }

  @Test
  @SuppressWarnings("deprecation")
  void execute_clusteringKeyGiven_shouldCallRecoverRecordWithClusteringKey() throws Exception {
    // Arrange
    when(manager.recoverRecord(eq("ns"), eq("tbl"), any(Key.class), nullable(Key.class)))
        .thenReturn(true);

    Result scanResult = mock(Result.class);
    when(scanResult.getPartitionKey()).thenReturn(Optional.of(Key.ofText("id", "pk1")));
    when(scanResult.getClusteringKey()).thenReturn(Optional.of(Key.ofInt("age", 20)));

    RecordFinalizer finalizer = new RecordFinalizer(manager);

    // Act
    finalizer.execute("ns", "tbl", scanResult);

    // Assert
    ArgumentCaptor<Key> pkCaptor = ArgumentCaptor.forClass(Key.class);
    ArgumentCaptor<Key> ckCaptor = ArgumentCaptor.forClass(Key.class);
    verify(manager).recoverRecord(eq("ns"), eq("tbl"), pkCaptor.capture(), ckCaptor.capture());
    assertThat((Object) pkCaptor.getValue()).isEqualTo(Key.ofText("id", "pk1"));
    assertThat((Object) ckCaptor.getValue()).isEqualTo(Key.ofInt("age", 20));
  }
}
