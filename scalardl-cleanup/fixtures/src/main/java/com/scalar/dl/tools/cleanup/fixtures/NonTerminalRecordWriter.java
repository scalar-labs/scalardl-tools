package com.scalar.dl.tools.cleanup.fixtures;

import com.scalar.db.api.ConditionBuilder;
import com.scalar.db.api.Consistency;
import com.scalar.db.api.Delete;
import com.scalar.db.api.DistributedStorage;
import com.scalar.db.api.DistributedStorageAdmin;
import com.scalar.db.api.Get;
import com.scalar.db.api.GetBuilder;
import com.scalar.db.api.Insert;
import com.scalar.db.api.Mutation;
import com.scalar.db.api.Put;
import com.scalar.db.api.PutBuilder;
import com.scalar.db.api.Result;
import com.scalar.db.api.TableMetadata;
import com.scalar.db.api.TransactionState;
import com.scalar.db.exception.storage.ExecutionException;
import com.scalar.db.io.Column;
import com.scalar.db.io.IntColumn;
import com.scalar.db.io.Key;
import com.scalar.db.transaction.consensuscommit.Attribute;
import com.scalar.db.transaction.consensuscommit.ConsensusCommitUtils;
import com.scalar.db.transaction.consensuscommit.CoordinatorStateAccessor;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Optional;
import java.util.UUID;

/**
 * Writes the records a Consensus Commit transaction leaves behind when its writer dies between
 * prepare and commit, straight through {@link DistributedStorage}.
 *
 * <p>ScalarDB removed the two-phase commit API in 3.20.0, so a fixture can no longer get such
 * records by preparing a transaction and never committing it. Writing the records directly is what
 * ScalarDB's own integration tests do, and it relies only on the public storage API and the record
 * format, which stays the same across versions.
 *
 * <p>The records match what {@code PrepareMutationComposer} in ScalarDB writes: the after-image
 * with {@code tx_state} set to PREPARED (or DELETED for a delete), the next {@code tx_version}, and
 * for a record that existed, the before-image copied from the committed record. Like a real
 * prepare, no Coordinator state is written; {@link #putCoordinatorState} adds one when a test needs
 * the transaction to have been decided.
 */
public final class NonTerminalRecordWriter {

  private final DistributedStorage storage;
  private final DistributedStorageAdmin storageAdmin;

  @SuppressFBWarnings("EI_EXPOSE_REP2")
  public NonTerminalRecordWriter(DistributedStorage storage, DistributedStorageAdmin storageAdmin) {
    this.storage = storage;
    this.storageAdmin = storageAdmin;
  }

  /**
   * Returns a new transaction ID. Random, because the Coordinator table can be shared with ScalarDL
   * and outlive a fixture, so a fixed ID could run into a state row from an earlier run.
   */
  public static String newTransactionId() {
    return UUID.randomUUID().toString();
  }

  /**
   * Writes the record of the insert as PREPARED by the given transaction. The record must not
   * exist.
   */
  public void writePreparedInsert(String transactionId, Insert insert) throws ExecutionException {
    PutBuilder.Buildable put = newPut(insert, transactionId, TransactionState.PREPARED);
    insert.getColumns().values().forEach(put::value);
    put.intValue(Attribute.VERSION, 1);
    put.condition(ConditionBuilder.putIfNotExists());
    storage.put(put.build());
  }

  /**
   * Marks the record of the delete as DELETED by the given transaction, keeping the committed
   * record as its before-image. The record must exist and be committed.
   */
  public void writePreparedDelete(String transactionId, Delete delete) throws ExecutionException {
    Result current = readCommitted(delete);
    TableMetadata metadata =
        storageAdmin.getTableMetadata(delete.forNamespace().get(), delete.forTable().get());

    PutBuilder.Buildable put = newPut(delete, transactionId, TransactionState.DELETED);
    for (Column<?> column : current.getColumns().values()) {
      String name = column.getName();
      if (metadata.getPartitionKeyNames().contains(name)
          || metadata.getClusteringKeyNames().contains(name)
          || !ConsensusCommitUtils.isAfterImageColumn(name, metadata)) {
        continue;
      }
      if (name.equals(Attribute.VERSION) && column.hasNullValue()) {
        // As ScalarDB does: version 0 rather than NULL, so that a rollback restores the record
        // instead of deleting it as one that did not exist.
        put.value(IntColumn.of(Attribute.BEFORE_VERSION, 0));
      } else {
        put.value(column.copyWith(Attribute.BEFORE_PREFIX + name));
      }
    }
    put.intValue(
        Attribute.VERSION,
        current.isNull(Attribute.VERSION) ? 1 : current.getInt(Attribute.VERSION) + 1);
    String currentId = current.getText(Attribute.ID);
    put.condition(
        currentId == null
            ? ConditionBuilder.putIf(ConditionBuilder.column(Attribute.ID).isNullText()).build()
            : ConditionBuilder.putIf(ConditionBuilder.column(Attribute.ID).isEqualToText(currentId))
                .build());
    storage.put(put.build());
  }

  /**
   * Writes the Coordinator state of the given transaction, as if it had been decided. Uses the
   * default Coordinator namespace.
   */
  public void putCoordinatorState(String transactionId, TransactionState state)
      throws ExecutionException {
    storage.put(
        Put.newBuilder()
            .namespace(CoordinatorStateAccessor.NAMESPACE)
            .table(CoordinatorStateAccessor.TABLE)
            .partitionKey(Key.ofText(Attribute.ID, transactionId))
            .intValue(Attribute.STATE, state.get())
            .bigIntValue(Attribute.CREATED_AT, System.currentTimeMillis())
            .consistency(Consistency.LINEARIZABLE)
            .build());
  }

  private Result readCommitted(Delete delete) throws ExecutionException {
    GetBuilder.BuildableGetWithPartitionKey get =
        Get.newBuilder()
            .namespace(delete.forNamespace().get())
            .table(delete.forTable().get())
            .partitionKey(delete.getPartitionKey())
            .consistency(Consistency.LINEARIZABLE);
    delete.getClusteringKey().ifPresent(get::clusteringKey);
    Optional<Result> result = storage.get(get.build());
    if (result.isEmpty()) {
      throw new IllegalStateException("No record to delete: " + delete);
    }
    if (!result.get().isNull(Attribute.STATE)
        && result.get().getInt(Attribute.STATE) != TransactionState.COMMITTED.get()) {
      throw new IllegalStateException("The record to delete is not committed: " + delete);
    }
    return result.get();
  }

  private static PutBuilder.Buildable newPut(
      Mutation base, String transactionId, TransactionState state) {
    PutBuilder.Buildable put =
        Put.newBuilder()
            .namespace(base.forNamespace().get())
            .table(base.forTable().get())
            .partitionKey(base.getPartitionKey())
            .consistency(Consistency.LINEARIZABLE);
    base.getClusteringKey().ifPresent(put::clusteringKey);
    put.textValue(Attribute.ID, transactionId);
    put.intValue(Attribute.STATE, state.get());
    put.bigIntValue(Attribute.PREPARED_AT, System.currentTimeMillis());
    return put;
  }
}
