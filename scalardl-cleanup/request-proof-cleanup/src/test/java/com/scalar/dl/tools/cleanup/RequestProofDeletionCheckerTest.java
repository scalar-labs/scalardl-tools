package com.scalar.dl.tools.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.scalar.db.api.Result;
import com.scalar.dl.tools.common.AuditorInternalValues;
import org.junit.jupiter.api.Test;

class RequestProofDeletionCheckerTest {

  private static final long DELETABLE_BEFORE_MS = 2000L;
  private static final String REGISTERED_AT =
      AuditorInternalValues.REQUEST_PROOF_TABLE_REGISTERED_AT_COLUMN_NAME;

  private final RequestProofDeletionChecker checker =
      new RequestProofDeletionChecker(DELETABLE_BEFORE_MS);

  private Result createResult(long registeredAt) {
    Result result = mock(Result.class);
    when(result.isNull(REGISTERED_AT)).thenReturn(false);
    when(result.getBigInt(REGISTERED_AT)).thenReturn(registeredAt);
    return result;
  }

  @Test
  void isDeletable_registeredBeforeBoundaryGiven_shouldReturnTrue() {
    // Act & Assert
    assertThat(checker.isDeletable(createResult(DELETABLE_BEFORE_MS - 1))).isTrue();
  }

  @Test
  void isDeletable_registeredAtExactlyBoundaryGiven_shouldReturnFalse() {
    // Act & Assert
    assertThat(checker.isDeletable(createResult(DELETABLE_BEFORE_MS))).isFalse();
  }

  @Test
  void isDeletable_registeredAfterBoundaryGiven_shouldReturnFalse() {
    // Act & Assert
    assertThat(checker.isDeletable(createResult(DELETABLE_BEFORE_MS + 1))).isFalse();
  }

  @Test
  void isDeletable_registeredAtIsNullGiven_shouldReturnFalse() {
    // Arrange
    Result result = mock(Result.class);
    when(result.isNull(REGISTERED_AT)).thenReturn(true);

    // Act & Assert
    assertThat(checker.isDeletable(result)).isFalse();
  }

  @Test
  void isDeletable_resultWithoutRegisteredAtColumnGiven_shouldThrowException() {
    // Arrange
    Result result = mock(Result.class);
    when(result.isNull(REGISTERED_AT))
        .thenThrow(new IllegalArgumentException("The column registered_at does not exist"));

    // Act & Assert
    assertThatThrownBy(() -> checker.isDeletable(result))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
