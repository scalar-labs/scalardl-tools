package com.scalar.dl.tools.cleanup;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.scalar.db.api.Result;
import com.scalar.db.api.TransactionState;
import com.scalar.db.io.Key;
import com.scalar.db.transaction.consensuscommit.Attribute;
import java.util.Optional;

/** Builds the records that the tests of the verification scan. */
final class VerifyTestRecords {

  private VerifyTestRecords() {}

  /** Returns a record in the given state that was prepared at the given time. */
  @SuppressWarnings("deprecation")
  static Result record(TransactionState state, long preparedAt) {
    // Stub-only, because the concurrency tests hand the same record to a handler many times.
    Result result = mock(Result.class, withSettings().stubOnly());
    when(result.isNull(Attribute.STATE)).thenReturn(false);
    when(result.getInt(Attribute.STATE)).thenReturn(state.get());
    when(result.isNull(Attribute.PREPARED_AT)).thenReturn(false);
    when(result.getBigInt(Attribute.PREPARED_AT)).thenReturn(preparedAt);
    when(result.getText(Attribute.ID)).thenReturn("tx-1");
    when(result.getPartitionKey()).thenReturn(Optional.of(Key.ofText("pk", "pk-1")));
    when(result.getClusteringKey()).thenReturn(Optional.empty());
    return result;
  }

  static Result committed() {
    return record(TransactionState.COMMITTED, 500L);
  }

  static Result prepared() {
    return record(TransactionState.PREPARED, 500L);
  }
}
