package com.scalar.dl.tools.scan.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scalar.db.api.TableMetadata;
import com.scalar.db.io.BigIntColumn;
import com.scalar.db.io.BlobColumn;
import com.scalar.db.io.BooleanColumn;
import com.scalar.db.io.Column;
import com.scalar.db.io.DataType;
import com.scalar.db.io.DateColumn;
import com.scalar.db.io.DoubleColumn;
import com.scalar.db.io.FloatColumn;
import com.scalar.db.io.IntColumn;
import com.scalar.db.io.TextColumn;
import com.scalar.db.io.TimeColumn;
import com.scalar.db.io.TimestampColumn;
import com.scalar.db.io.TimestampTZColumn;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class PrimaryKeySerializerTest {

  private static final TableMetadata ALL_TYPES_METADATA =
      TableMetadata.newBuilder()
          .addColumn("c_boolean", DataType.BOOLEAN)
          .addColumn("c_int", DataType.INT)
          .addColumn("c_bigint", DataType.BIGINT)
          .addColumn("c_float", DataType.FLOAT)
          .addColumn("c_double", DataType.DOUBLE)
          .addColumn("c_text", DataType.TEXT)
          .addColumn("c_blob", DataType.BLOB)
          .addColumn("c_date", DataType.DATE)
          .addColumn("c_time", DataType.TIME)
          .addColumn("c_timestamp", DataType.TIMESTAMP)
          .addColumn("c_timestamptz", DataType.TIMESTAMPTZ)
          .addPartitionKey("c_boolean")
          .addPartitionKey("c_int")
          .addClusteringKey("c_bigint")
          .addClusteringKey("c_float")
          .addClusteringKey("c_double")
          .addClusteringKey("c_text")
          .addClusteringKey("c_blob")
          .addClusteringKey("c_date")
          .addClusteringKey("c_time")
          .addClusteringKey("c_timestamp")
          .addClusteringKey("c_timestamptz")
          .build();

  private static final TableMetadata TEXT_KEY_METADATA =
      TableMetadata.newBuilder()
          .addColumn("pk", DataType.TEXT)
          .addColumn("ck", DataType.INT)
          .addPartitionKey("pk")
          .addClusteringKey("ck")
          .build();

  @Test
  void toJsonAndFromJson_allDataTypesGiven_shouldRoundTrip() {
    // Arrange
    List<Column<?>> key =
        Arrays.asList(
            BooleanColumn.of("c_boolean", true),
            IntColumn.of("c_int", Integer.MIN_VALUE),
            BigIntColumn.of("c_bigint", 9_007_199_254_740_991L),
            FloatColumn.of("c_float", 0.1f),
            DoubleColumn.of("c_double", 0.1d),
            TextColumn.of("c_text", "テキスト\"\n"),
            BlobColumn.of("c_blob", "blob".getBytes(StandardCharsets.UTF_8)),
            DateColumn.of("c_date", LocalDate.of(2026, 10, 14)),
            TimeColumn.of("c_time", LocalTime.of(12, 34, 56, 789_000_000)),
            TimestampColumn.of(
                "c_timestamp", LocalDateTime.of(2026, 10, 14, 12, 34, 56, 789_000_000)),
            TimestampTZColumn.of("c_timestamptz", Instant.parse("2026-10-14T03:34:56.789Z")));

    // Act
    List<Column<?>> restored =
        PrimaryKeySerializer.fromJson(PrimaryKeySerializer.toJson(key), ALL_TYPES_METADATA);

    // Assert
    assertThat(restored).isEqualTo(key);
  }

  @Test
  void toJson_nullValueGiven_shouldThrowIllegalArgumentException() {
    // Arrange
    List<Column<?>> key = Collections.singletonList(TextColumn.ofNull("pk"));

    // Act & Assert
    assertThatThrownBy(() -> PrimaryKeySerializer.toJson(key))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void fromJson_malformedJsonGiven_shouldThrowIllegalStateException() {
    assertThatThrownBy(() -> PrimaryKeySerializer.fromJson("{not json", TEXT_KEY_METADATA))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void fromJson_columnCountMismatchGiven_shouldThrowIllegalStateException() {
    // Arrange
    String json = PrimaryKeySerializer.toJson(Collections.singletonList(TextColumn.of("pk", "a")));

    // Act & Assert
    assertThatThrownBy(() -> PrimaryKeySerializer.fromJson(json, TEXT_KEY_METADATA))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void fromJson_columnNameMismatchGiven_shouldThrowIllegalStateException() {
    // Arrange
    String json =
        PrimaryKeySerializer.toJson(Arrays.asList(TextColumn.of("pk", "a"), IntColumn.of("x", 1)));

    // Act & Assert
    assertThatThrownBy(() -> PrimaryKeySerializer.fromJson(json, TEXT_KEY_METADATA))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void fromJson_dataTypeMismatchGiven_shouldThrowIllegalStateException() {
    // Arrange
    String json =
        PrimaryKeySerializer.toJson(
            Arrays.asList(TextColumn.of("pk", "a"), BigIntColumn.of("ck", 1L)));

    // Act & Assert
    assertThatThrownBy(() -> PrimaryKeySerializer.fromJson(json, TEXT_KEY_METADATA))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void fromJson_invalidValueGiven_shouldThrowIllegalStateException() {
    // Arrange
    String json =
        "[{\"name\":\"pk\",\"type\":\"TEXT\",\"value\":\"a\"},"
            + "{\"name\":\"ck\",\"type\":\"INT\",\"value\":\"not-an-int\"}]";

    // Act & Assert
    assertThatThrownBy(() -> PrimaryKeySerializer.fromJson(json, TEXT_KEY_METADATA))
        .isInstanceOf(IllegalStateException.class);
  }
}
