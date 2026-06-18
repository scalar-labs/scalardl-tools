package com.scalar.dl.tools.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.scalar.db.api.Result;
import com.scalar.db.transaction.consensuscommit.Attribute;
import org.junit.jupiter.api.Test;

class RecordDeletionCheckerTest {

  private static final long DELETABLE_BEFORE_MS = 2000L;

  private final RecordDeletionChecker checker = new RecordDeletionChecker(DELETABLE_BEFORE_MS);

  private Result createResult(long createdAt) {
    Result result = mock(Result.class);
    when(result.isNull(Attribute.CREATED_AT)).thenReturn(false);
    when(result.getBigInt(Attribute.CREATED_AT)).thenReturn(createdAt);
    return result;
  }

  @Test
  void isDeletable_createdBeforeBoundaryGiven_shouldReturnTrue() {
    // Act & Assert
    assertThat(checker.isDeletable(createResult(DELETABLE_BEFORE_MS - 1))).isTrue();
  }

  @Test
  void isDeletable_createdAtExactlyBoundaryGiven_shouldReturnFalse() {
    // Act & Assert
    assertThat(checker.isDeletable(createResult(DELETABLE_BEFORE_MS))).isFalse();
  }

  @Test
  void isDeletable_createdAfterBoundaryGiven_shouldReturnFalse() {
    // Act & Assert
    assertThat(checker.isDeletable(createResult(DELETABLE_BEFORE_MS + 1))).isFalse();
  }

  @Test
  void isDeletable_createdAtIsNullGiven_shouldReturnFalse() {
    // Arrange
    Result result = mock(Result.class);
    when(result.isNull(Attribute.CREATED_AT)).thenReturn(true);

    // Act & Assert
    assertThat(checker.isDeletable(result)).isFalse();
  }

  @Test
  void isDeletable_resultWithoutCreatedAtColumnGiven_shouldThrowException() {
    // Arrange
    Result result = mock(Result.class);
    when(result.isNull(Attribute.CREATED_AT))
        .thenThrow(new IllegalArgumentException("The column tx_created_at does not exist"));

    // Act & Assert
    assertThatThrownBy(() -> checker.isDeletable(result))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
