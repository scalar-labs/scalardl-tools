package com.scalar.dl.tools.cleanup.fixtures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.scalar.db.api.ConditionBuilder;
import com.scalar.db.api.Delete;
import com.scalar.db.api.DistributedStorage;
import com.scalar.db.api.DistributedStorageAdmin;
import com.scalar.db.api.Get;
import com.scalar.db.api.Insert;
import com.scalar.db.api.Put;
import com.scalar.db.api.TableMetadata;
import com.scalar.db.api.TransactionState;
import com.scalar.db.common.ResultImpl;
import com.scalar.db.io.BigIntColumn;
import com.scalar.db.io.Column;
import com.scalar.db.io.DataType;
import com.scalar.db.io.IntColumn;
import com.scalar.db.io.Key;
import com.scalar.db.io.TextColumn;
import com.scalar.db.transaction.consensuscommit.Attribute;
import com.scalar.db.transaction.consensuscommit.ConsensusCommitUtils;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

public class NonTerminalRecordWriterTest {

  private static final String NAMESPACE = "ns";
  private static final String TABLE = "tbl";
  private static final String TX_ID = "tx-1";
  private static final TableMetadata METADATA =
      ConsensusCommitUtils.buildTransactionTableMetadata(
          TableMetadata.newBuilder()
              .addColumn("pk", DataType.TEXT)
              .addColumn("ck", DataType.INT)
              .addColumn("val", DataType.TEXT)
              .addPartitionKey("pk")
              .addClusteringKey("ck")
              .build());

  private DistributedStorage storage;
  private DistributedStorageAdmin storageAdmin;
  private NonTerminalRecordWriter writer;

  @BeforeEach
  void setUp() throws Exception {
    storage = mock(DistributedStorage.class);
    storageAdmin = mock(DistributedStorageAdmin.class);
    when(storageAdmin.getTableMetadata(NAMESPACE, TABLE)).thenReturn(METADATA);
    writer = new NonTerminalRecordWriter(storage, storageAdmin);
  }

  @Test
  public void writePreparedInsert_shouldWriteAfterImageAsPreparedWithoutBeforeImage()
      throws Exception {
    // Arrange
    Insert insert =
        Insert.newBuilder()
            .namespace(NAMESPACE)
            .table(TABLE)
            .partitionKey(Key.ofText("pk", "a"))
            .clusteringKey(Key.ofInt("ck", 1))
            .textValue("val", "new")
            .build();

    // Act
    writer.writePreparedInsert(TX_ID, insert);

    // Assert
    Put put = capturePut();
    assertThat(put.forNamespace()).hasValue(NAMESPACE);
    assertThat(put.forTable()).hasValue(TABLE);
    assertThat((Object) put.getPartitionKey()).isEqualTo(Key.ofText("pk", "a"));
    assertThat(put.getClusteringKey()).hasValue(Key.ofInt("ck", 1));
    Map<String, Column<?>> columns = put.getColumns();
    assertThat(columns.get("val")).isEqualTo(TextColumn.of("val", "new"));
    assertThat(columns.get(Attribute.ID)).isEqualTo(TextColumn.of(Attribute.ID, TX_ID));
    assertThat(columns.get(Attribute.STATE))
        .isEqualTo(IntColumn.of(Attribute.STATE, TransactionState.PREPARED.get()));
    assertThat(columns.get(Attribute.VERSION)).isEqualTo(IntColumn.of(Attribute.VERSION, 1));
    assertThat(columns).containsKey(Attribute.PREPARED_AT);
    assertThat(columns.keySet()).noneMatch(name -> name.startsWith(Attribute.BEFORE_PREFIX));
    assertThat(put.getCondition()).hasValue(ConditionBuilder.putIfNotExists());
  }

  @Test
  public void writePreparedDelete_shouldMarkRecordDeletedWithCommittedRecordAsBeforeImage()
      throws Exception {
    // Arrange
    Map<String, Column<?>> committed = new HashMap<>();
    committed.put("pk", TextColumn.of("pk", "a"));
    committed.put("ck", IntColumn.of("ck", 1));
    committed.put("val", TextColumn.of("val", "old"));
    committed.put(Attribute.ID, TextColumn.of(Attribute.ID, "committer"));
    committed.put(Attribute.STATE, IntColumn.of(Attribute.STATE, TransactionState.COMMITTED.get()));
    committed.put(Attribute.VERSION, IntColumn.of(Attribute.VERSION, 3));
    committed.put(Attribute.PREPARED_AT, BigIntColumn.of(Attribute.PREPARED_AT, 10L));
    committed.put(Attribute.COMMITTED_AT, BigIntColumn.of(Attribute.COMMITTED_AT, 20L));
    committed.put(Attribute.BEFORE_ID, TextColumn.ofNull(Attribute.BEFORE_ID));
    when(storage.get(any(Get.class))).thenReturn(Optional.of(new ResultImpl(committed, METADATA)));

    // Act
    writer.writePreparedDelete(TX_ID, delete());

    // Assert
    Put put = capturePut();
    Map<String, Column<?>> columns = put.getColumns();
    assertThat(columns.get(Attribute.ID)).isEqualTo(TextColumn.of(Attribute.ID, TX_ID));
    assertThat(columns.get(Attribute.STATE))
        .isEqualTo(IntColumn.of(Attribute.STATE, TransactionState.DELETED.get()));
    assertThat(columns.get(Attribute.VERSION)).isEqualTo(IntColumn.of(Attribute.VERSION, 4));
    assertThat(columns.get("before_val")).isEqualTo(TextColumn.of("before_val", "old"));
    assertThat(columns.get(Attribute.BEFORE_ID))
        .isEqualTo(TextColumn.of(Attribute.BEFORE_ID, "committer"));
    assertThat(columns.get(Attribute.BEFORE_STATE))
        .isEqualTo(IntColumn.of(Attribute.BEFORE_STATE, TransactionState.COMMITTED.get()));
    assertThat(columns.get(Attribute.BEFORE_VERSION))
        .isEqualTo(IntColumn.of(Attribute.BEFORE_VERSION, 3));
    assertThat(columns.get(Attribute.BEFORE_PREPARED_AT))
        .isEqualTo(BigIntColumn.of(Attribute.BEFORE_PREPARED_AT, 10L));
    assertThat(columns.get(Attribute.BEFORE_COMMITTED_AT))
        .isEqualTo(BigIntColumn.of(Attribute.BEFORE_COMMITTED_AT, 20L));
    // The after-image of a delete carries no user columns, and the keys are not copied.
    assertThat(columns).doesNotContainKeys("val", "before_pk", "before_ck");
    assertThat(put.getCondition())
        .hasValue(
            ConditionBuilder.putIf(ConditionBuilder.column(Attribute.ID).isEqualToText("committer"))
                .build());
  }

  @Test
  public void writePreparedDelete_recordWithNullMetadataGiven_shouldUseVersionZeroAsBeforeVersion()
      throws Exception {
    // Arrange: a record regarded as committed although no transaction wrote it
    Map<String, Column<?>> imported = new HashMap<>();
    imported.put("pk", TextColumn.of("pk", "a"));
    imported.put("ck", IntColumn.of("ck", 1));
    imported.put("val", TextColumn.of("val", "old"));
    imported.put(Attribute.ID, TextColumn.ofNull(Attribute.ID));
    imported.put(Attribute.STATE, IntColumn.ofNull(Attribute.STATE));
    imported.put(Attribute.VERSION, IntColumn.ofNull(Attribute.VERSION));
    when(storage.get(any(Get.class))).thenReturn(Optional.of(new ResultImpl(imported, METADATA)));

    // Act
    writer.writePreparedDelete(TX_ID, delete());

    // Assert
    Put put = capturePut();
    Map<String, Column<?>> columns = put.getColumns();
    assertThat(columns.get(Attribute.VERSION)).isEqualTo(IntColumn.of(Attribute.VERSION, 1));
    assertThat(columns.get(Attribute.BEFORE_VERSION))
        .isEqualTo(IntColumn.of(Attribute.BEFORE_VERSION, 0));
    assertThat(put.getCondition())
        .hasValue(
            ConditionBuilder.putIf(ConditionBuilder.column(Attribute.ID).isNullText()).build());
  }

  @Test
  public void writePreparedDelete_tableWithoutClusteringKeyGiven_shouldReadAndWriteByPartitionKey()
      throws Exception {
    // Arrange: the shape of the E2E's table, which has a partition key only
    String table = "pk_only";
    TableMetadata metadata =
        ConsensusCommitUtils.buildTransactionTableMetadata(
            TableMetadata.newBuilder()
                .addColumn("pk", DataType.TEXT)
                .addColumn("val", DataType.TEXT)
                .addPartitionKey("pk")
                .build());
    when(storageAdmin.getTableMetadata(NAMESPACE, table)).thenReturn(metadata);
    Map<String, Column<?>> committed = new HashMap<>();
    committed.put("pk", TextColumn.of("pk", "a"));
    committed.put("val", TextColumn.of("val", "old"));
    committed.put(Attribute.ID, TextColumn.of(Attribute.ID, "committer"));
    committed.put(Attribute.STATE, IntColumn.of(Attribute.STATE, TransactionState.COMMITTED.get()));
    committed.put(Attribute.VERSION, IntColumn.of(Attribute.VERSION, 1));
    when(storage.get(any(Get.class))).thenReturn(Optional.of(new ResultImpl(committed, metadata)));

    // Act
    writer.writePreparedDelete(
        TX_ID,
        Delete.newBuilder()
            .namespace(NAMESPACE)
            .table(table)
            .partitionKey(Key.ofText("pk", "a"))
            .build());

    // Assert
    ArgumentCaptor<Get> get = ArgumentCaptor.forClass(Get.class);
    verify(storage).get(get.capture());
    assertThat((Object) get.getValue().getPartitionKey()).isEqualTo(Key.ofText("pk", "a"));
    assertThat(get.getValue().getClusteringKey()).isEmpty();
    Put put = capturePut();
    assertThat(put.forTable()).hasValue(table);
    assertThat((Object) put.getPartitionKey()).isEqualTo(Key.ofText("pk", "a"));
    assertThat(put.getClusteringKey()).isEmpty();
    Map<String, Column<?>> columns = put.getColumns();
    assertThat(columns.get(Attribute.STATE))
        .isEqualTo(IntColumn.of(Attribute.STATE, TransactionState.DELETED.get()));
    assertThat(columns.get(Attribute.VERSION)).isEqualTo(IntColumn.of(Attribute.VERSION, 2));
    assertThat(columns.get("before_val")).isEqualTo(TextColumn.of("before_val", "old"));
    assertThat(columns).doesNotContainKey("before_pk");
  }

  @Test
  public void writePreparedDelete_noRecordGiven_shouldThrowIllegalStateException()
      throws Exception {
    // Arrange
    when(storage.get(any(Get.class))).thenReturn(Optional.empty());

    // Act Assert
    assertThatThrownBy(() -> writer.writePreparedDelete(TX_ID, delete()))
        .isInstanceOf(IllegalStateException.class);
    verify(storage, never()).put(any(Put.class));
  }

  @Test
  public void writePreparedDelete_preparedRecordGiven_shouldThrowIllegalStateException()
      throws Exception {
    // Arrange
    Map<String, Column<?>> prepared = new HashMap<>();
    prepared.put("pk", TextColumn.of("pk", "a"));
    prepared.put("ck", IntColumn.of("ck", 1));
    prepared.put(Attribute.ID, TextColumn.of(Attribute.ID, "other"));
    prepared.put(Attribute.STATE, IntColumn.of(Attribute.STATE, TransactionState.PREPARED.get()));
    prepared.put(Attribute.VERSION, IntColumn.of(Attribute.VERSION, 1));
    when(storage.get(any(Get.class))).thenReturn(Optional.of(new ResultImpl(prepared, METADATA)));

    // Act Assert
    assertThatThrownBy(() -> writer.writePreparedDelete(TX_ID, delete()))
        .isInstanceOf(IllegalStateException.class);
    verify(storage, never()).put(any(Put.class));
  }

  @Test
  public void putCoordinatorState_shouldWriteStateOfTransaction() throws Exception {
    // Act
    writer.putCoordinatorState(TX_ID, TransactionState.COMMITTED);

    // Assert
    Put put = capturePut();
    assertThat(put.forNamespace()).hasValue("coordinator");
    assertThat(put.forTable()).hasValue("state");
    assertThat((Object) put.getPartitionKey()).isEqualTo(Key.ofText(Attribute.ID, TX_ID));
    assertThat(put.getColumns().get(Attribute.STATE))
        .isEqualTo(IntColumn.of(Attribute.STATE, TransactionState.COMMITTED.get()));
  }

  private static Delete delete() {
    return Delete.newBuilder()
        .namespace(NAMESPACE)
        .table(TABLE)
        .partitionKey(Key.ofText("pk", "a"))
        .clusteringKey(Key.ofInt("ck", 1))
        .build();
  }

  private Put capturePut() throws Exception {
    ArgumentCaptor<Put> captor = ArgumentCaptor.forClass(Put.class);
    verify(storage).put(captor.capture());
    return captor.getValue();
  }
}
