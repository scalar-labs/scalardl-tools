package com.scalar.dl.tools.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.scalar.db.api.Result;
import com.scalar.dl.tools.common.AuditorInternalValues;
import org.junit.jupiter.api.Test;

class LockStateCheckerTest {

  private static final long GUARANTEE_TS = 1000L;

  private final LockStateChecker checker = new LockStateChecker(GUARANTEE_TS);

  private Result createResult(int lockType, long lastUpdatedAt) {
    Result result = mock(Result.class);
    when(result.getInt(AuditorInternalValues.ASSET_LOCK_TABLE_LOCK_TYPE_COLUMN_NAME))
        .thenReturn(lockType);
    when(result.getBigInt(AuditorInternalValues.ASSET_LOCK_TABLE_LAST_UPDATED_AT_COLUMN_NAME))
        .thenReturn(lastUpdatedAt);
    return result;
  }

  @Test
  void needsFinalization_writeLockBeforeGuaranteeGiven_shouldReturnTrue() {
    // Act & Assert
    assertThat(
            checker.needsFinalization(
                createResult(AuditorInternalValues.LOCK_TYPE_WRITE, GUARANTEE_TS - 1)))
        .isTrue();
  }

  @Test
  void needsFinalization_readLockBeforeGuaranteeGiven_shouldReturnTrue() {
    // Act & Assert
    assertThat(
            checker.needsFinalization(
                createResult(AuditorInternalValues.LOCK_TYPE_READ, GUARANTEE_TS - 1)))
        .isTrue();
  }

  @Test
  void needsFinalization_readLockAfterGuaranteeGiven_shouldReturnTrue() {
    // Read locks are always finalized regardless of last_updated_at, because a refreshed
    // last_updated_at can mask a stranded read owner (see LockStateChecker).
    assertThat(
            checker.needsFinalization(
                createResult(AuditorInternalValues.LOCK_TYPE_READ, GUARANTEE_TS + 1)))
        .isTrue();
  }

  @Test
  void needsFinalization_noneLockGiven_shouldReturnFalse() {
    // Act & Assert
    assertThat(
            checker.needsFinalization(
                createResult(AuditorInternalValues.LOCK_TYPE_NONE, GUARANTEE_TS - 1)))
        .isFalse();
  }

  @Test
  void needsFinalization_writeLockAfterGuaranteeGiven_shouldReturnFalse() {
    // Act & Assert
    assertThat(
            checker.needsFinalization(
                createResult(AuditorInternalValues.LOCK_TYPE_WRITE, GUARANTEE_TS + 1)))
        .isFalse();
  }

  @Test
  void needsFinalization_writeLockAtExactlyGuaranteeGiven_shouldReturnFalse() {
    // Act & Assert
    assertThat(
            checker.needsFinalization(
                createResult(AuditorInternalValues.LOCK_TYPE_WRITE, GUARANTEE_TS)))
        .isFalse();
  }

  @Test
  void needsFinalization_unknownLockTypeGiven_shouldThrowException() {
    // Arrange — a lock_type that is neither NONE, READ, nor WRITE.
    int unknownLockType = 99;

    // Act & Assert
    assertThatThrownBy(() -> checker.needsFinalization(createResult(unknownLockType, GUARANTEE_TS)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Unexpected lock_type");
  }

  @Test
  void needsFinalization_resultWithoutLockMetadataGiven_shouldThrowException() {
    // Arrange
    Result result = mock(Result.class);
    when(result.getInt(AuditorInternalValues.ASSET_LOCK_TABLE_LOCK_TYPE_COLUMN_NAME))
        .thenThrow(new IllegalArgumentException("The column lock_type does not exist"));

    // Act & Assert
    assertThatThrownBy(() -> checker.needsFinalization(result))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
