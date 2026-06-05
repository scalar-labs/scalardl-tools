package com.scalar.dl.tools.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.scalar.db.api.Result;
import com.scalar.db.api.TransactionState;
import com.scalar.db.io.BigIntColumn;
import com.scalar.db.io.IntColumn;
import com.scalar.db.transaction.consensuscommit.Attribute;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RecordStateCheckerTest {

  private static final long GUARANTEE_TS = 1000L;

  private final RecordStateChecker checker = new RecordStateChecker(GUARANTEE_TS);

  private Result createResult(TransactionState txState, long txPreparedAt) {
    Result result = mock(Result.class);
    Map<String, com.scalar.db.io.Column<?>> columns = new LinkedHashMap<>();
    columns.put(Attribute.STATE, IntColumn.of(Attribute.STATE, txState.get()));
    columns.put(Attribute.PREPARED_AT, BigIntColumn.of(Attribute.PREPARED_AT, txPreparedAt));
    when(result.getColumns()).thenReturn(columns);
    when(result.isNull(Attribute.STATE)).thenReturn(false);
    when(result.getInt(Attribute.STATE)).thenReturn(txState.get());
    when(result.isNull(Attribute.PREPARED_AT)).thenReturn(false);
    when(result.getBigInt(Attribute.PREPARED_AT)).thenReturn(txPreparedAt);
    return result;
  }

  private Result createCommitted() {
    return createResult(TransactionState.COMMITTED, 500L);
  }

  private Result createPreparedBeforeGuarantee() {
    return createResult(TransactionState.PREPARED, GUARANTEE_TS - 1);
  }

  private Result createDeletedBeforeGuarantee() {
    return createResult(TransactionState.DELETED, GUARANTEE_TS - 1);
  }

  private Result createPreparedAfterGuarantee() {
    return createResult(TransactionState.PREPARED, GUARANTEE_TS + 1);
  }

  @Test
  void needsFinalization_preparedBeforeGuaranteeGiven_shouldReturnTrue() {
    // Act & Assert
    assertThat(checker.needsFinalization(createPreparedBeforeGuarantee())).isTrue();
  }

  @Test
  void needsFinalization_deletedBeforeGuaranteeGiven_shouldReturnTrue() {
    // Act & Assert
    assertThat(checker.needsFinalization(createDeletedBeforeGuarantee())).isTrue();
  }

  @Test
  void needsFinalization_committedGiven_shouldReturnFalse() {
    // Act & Assert
    assertThat(checker.needsFinalization(createCommitted())).isFalse();
  }

  @Test
  void needsFinalization_preparedAfterGuaranteeGiven_shouldReturnFalse() {
    // Act & Assert
    assertThat(checker.needsFinalization(createPreparedAfterGuarantee())).isFalse();
  }

  @Test
  void needsFinalization_preparedAtExactlyGuaranteeGiven_shouldReturnFalse() {
    // Act & Assert
    assertThat(checker.needsFinalization(createResult(TransactionState.PREPARED, GUARANTEE_TS)))
        .isFalse();
  }

  @Test
  void needsFinalization_resultWithoutTransactionMetadataGiven_shouldThrowException() {
    // Arrange
    // Result without tx_state column
    Result result = mock(Result.class);
    when(result.getColumns()).thenReturn(Collections.emptyMap());
    when(result.isNull(Attribute.STATE))
        .thenThrow(new IllegalArgumentException("The column tx_state does not exist"));

    // Act & Assert
    assertThatThrownBy(() -> checker.needsFinalization(result))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
