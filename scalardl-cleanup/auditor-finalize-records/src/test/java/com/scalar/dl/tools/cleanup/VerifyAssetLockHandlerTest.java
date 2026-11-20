package com.scalar.dl.tools.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.scalar.db.api.Result;
import com.scalar.dl.tools.common.AuditorInternalValues;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VerifyAssetLockHandlerTest {

  private static final String NAMESPACE = "auditor";
  private static final int UNKNOWN_LOCK_TYPE = 99;

  private VerifyAssetLockHandler handler;

  @BeforeEach
  void setUp() {
    handler = new VerifyAssetLockHandler(NAMESPACE);
  }

  private static Result lockRecord(String assetId, int lockType, long lastUpdatedAt) {
    Result record = mock(Result.class);
    when(record.getText(AuditorInternalValues.ASSET_LOCK_TABLE_ID_COLUMN_NAME)).thenReturn(assetId);
    when(record.getInt(AuditorInternalValues.ASSET_LOCK_TABLE_LOCK_TYPE_COLUMN_NAME))
        .thenReturn(lockType);
    when(record.getBigInt(AuditorInternalValues.ASSET_LOCK_TABLE_LAST_UPDATED_AT_COLUMN_NAME))
        .thenReturn(lastUpdatedAt);
    return record;
  }

  @Test
  void handle_releasedLockGiven_shouldPassWithoutDetection() {
    // Arrange
    Result record = lockRecord("asset-1", AuditorInternalValues.LOCK_TYPE_NONE, 1L);

    // Act & Assert
    assertThatCode(() -> handler.handle(record)).doesNotThrowAnyException();
    assertThat(handler.isDetected()).isFalse();
  }

  @Test
  void handle_readLockGiven_shouldPassWithoutDetection() {
    // Arrange
    Result record = lockRecord("asset-1", AuditorInternalValues.LOCK_TYPE_READ, 1L);

    // Act & Assert
    assertThatCode(() -> handler.handle(record)).doesNotThrowAnyException();
    assertThat(handler.isDetected()).isFalse();
  }

  @Test
  void handle_writeLockGiven_shouldThrowAndMarkDetected() {
    // Arrange
    Result record = lockRecord("asset-1", AuditorInternalValues.LOCK_TYPE_WRITE, 1L);

    // Act & Assert
    assertThatThrownBy(() -> handler.handle(record)).hasMessageContaining("asset-1");
    assertThat(handler.isDetected()).isTrue();
  }

  @Test
  void handle_writeLockUpdatedJustNowGiven_shouldThrowAndMarkDetected() {
    // Arrange
    Result record =
        lockRecord("asset-1", AuditorInternalValues.LOCK_TYPE_WRITE, System.currentTimeMillis());

    // Act & Assert
    assertThatThrownBy(() -> handler.handle(record)).isInstanceOf(Exception.class);
    assertThat(handler.isDetected()).isTrue();
  }

  @Test
  void handle_unknownLockTypeGiven_shouldThrowIllegalStateExceptionWithoutDetection() {
    // Arrange
    Result record = lockRecord("asset-1", UNKNOWN_LOCK_TYPE, 1L);

    // Act & Assert
    assertThatThrownBy(() -> handler.handle(record))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Unexpected lock_type value in asset_lock record: 99");
    assertThat(handler.isDetected()).isFalse();
  }

  @Test
  void handle_recordsAfterDetectionGiven_shouldThrowForEveryLockType() {
    // Arrange
    assertThatThrownBy(
            () -> handler.handle(lockRecord("held", AuditorInternalValues.LOCK_TYPE_WRITE, 1L)))
        .isInstanceOf(Exception.class);

    // Act & Assert — released and read locks no longer pass, so every scan worker stops.
    assertThatThrownBy(
            () -> handler.handle(lockRecord("released", AuditorInternalValues.LOCK_TYPE_NONE, 1L)))
        .isInstanceOf(Exception.class);
    assertThatThrownBy(
            () -> handler.handle(lockRecord("read", AuditorInternalValues.LOCK_TYPE_READ, 1L)))
        .isInstanceOf(Exception.class);
  }

  @Test
  void handle_calledRepeatedlyAfterDetection_shouldThrowDistinctExceptionEachTime() {
    // Arrange
    Result held = lockRecord("held", AuditorInternalValues.LOCK_TYPE_WRITE, 1L);
    Result released = lockRecord("released", AuditorInternalValues.LOCK_TYPE_NONE, 1L);

    // Act
    Throwable first = catchThrowable(() -> handler.handle(held));
    Throwable second = catchThrowable(() -> handler.handle(released));
    Throwable third = catchThrowable(() -> handler.handle(released));

    // Assert — the scanner attaches the later failures to the first one as suppressed, which a
    // shared instance would not allow.
    assertThat(first).isNotNull();
    assertThat(second).isNotNull().isNotSameAs(first);
    assertThat(third).isNotNull().isNotSameAs(first).isNotSameAs(second);
    assertThatCode(
            () -> {
              first.addSuppressed(second);
              first.addSuppressed(third);
            })
        .doesNotThrowAnyException();
  }
}
