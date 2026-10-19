package com.scalar.dl.tools.scan.jdbc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/**
 * Serializes the primary key columns of a scanned record to JSON for checkpointing, and restores
 * them.
 *
 * <p>The JSON is an array of {@code {"name": ..., "type": ..., "value": ...}} objects in primary
 * key order (partition key columns followed by clustering key columns). FLOAT and DOUBLE values are
 * encoded as strings so that NaN and infinities, which JSON numbers cannot represent, round-trip
 * exactly. BLOB values are encoded in Base64 and date/time values in ISO-8601.
 */
final class PrimaryKeySerializer {

  private static final ObjectMapper mapper = new ObjectMapper();
  private static final String NAME = "name";
  private static final String TYPE = "type";
  private static final String VALUE = "value";

  private PrimaryKeySerializer() {}

  /** Serialize the given primary key columns to JSON. */
  static String toJson(List<Column<?>> primaryKey) {
    ArrayNode array = mapper.createArrayNode();
    for (Column<?> column : primaryKey) {
      if (column.hasNullValue()) {
        throw new IllegalArgumentException(
            "Primary key column must not be null: " + column.getName());
      }
      ObjectNode node = array.addObject();
      node.put(NAME, column.getName());
      node.put(TYPE, column.getDataType().name());
      putValue(node, column);
    }
    try {
      return mapper.writeValueAsString(array);
    } catch (IOException e) {
      throw new RuntimeException("Failed to serialize primary key", e);
    }
  }

  private static void putValue(ObjectNode node, Column<?> column) {
    switch (column.getDataType()) {
      case BOOLEAN -> node.put(VALUE, column.getBooleanValue());
      case INT -> node.put(VALUE, column.getIntValue());
      case BIGINT -> node.put(VALUE, column.getBigIntValue());
      case FLOAT -> node.put(VALUE, Float.toString(column.getFloatValue()));
      case DOUBLE -> node.put(VALUE, Double.toString(column.getDoubleValue()));
      case TEXT -> node.put(VALUE, column.getTextValue());
      case BLOB ->
          node.put(
              VALUE,
              Base64.getEncoder()
                  .encodeToString(Objects.requireNonNull(column.getBlobValueAsBytes())));
      case DATE -> node.put(VALUE, Objects.requireNonNull(column.getDateValue()).toString());
      case TIME -> node.put(VALUE, Objects.requireNonNull(column.getTimeValue()).toString());
      case TIMESTAMP ->
          node.put(VALUE, Objects.requireNonNull(column.getTimestampValue()).toString());
      case TIMESTAMPTZ ->
          node.put(VALUE, Objects.requireNonNull(column.getTimestampTZValue()).toString());
    }
  }

  /**
   * Restore primary key columns from JSON, validating them against the table's primary key.
   *
   * @throws IllegalStateException if the JSON is malformed or does not match the table's primary
   *     key columns (names, order, and data types)
   */
  static List<Column<?>> fromJson(String json, TableMetadata metadata) {
    List<String> primaryKeyColumnNames = JdbcResumableScanner.primaryKeyColumnNames(metadata);
    JsonNode array;
    try {
      array = mapper.readTree(json);
    } catch (IOException e) {
      throw new IllegalStateException("Malformed primary key checkpoint: " + json, e);
    }
    if (array == null || !array.isArray() || array.size() != primaryKeyColumnNames.size()) {
      throw new IllegalStateException(
          "Primary key checkpoint does not match the table's primary key "
              + primaryKeyColumnNames
              + ": "
              + json);
    }

    List<Column<?>> columns = new ArrayList<>(primaryKeyColumnNames.size());
    for (int i = 0; i < primaryKeyColumnNames.size(); i++) {
      String name = primaryKeyColumnNames.get(i);
      DataType type = metadata.getColumnDataType(name);
      JsonNode node = array.get(i);
      if (!name.equals(node.path(NAME).asText(null))
          || !type.name().equals(node.path(TYPE).asText(null))
          || !node.hasNonNull(VALUE)) {
        throw new IllegalStateException(
            "Primary key checkpoint does not match the table's primary key "
                + primaryKeyColumnNames
                + ": "
                + json);
      }
      try {
        columns.add(toColumn(name, type, node.get(VALUE)));
      } catch (RuntimeException e) {
        throw new IllegalStateException(
            "Malformed value for column " + name + " in primary key checkpoint: " + json, e);
      }
    }
    return columns;
  }

  private static Column<?> toColumn(String name, DataType type, JsonNode value) {
    boolean validJsonType =
        switch (type) {
          case BOOLEAN -> value.isBoolean();
          case INT -> value.isInt();
          case BIGINT -> value.isIntegralNumber() && value.canConvertToLong();
          case FLOAT, DOUBLE, TEXT, BLOB, DATE, TIME, TIMESTAMP, TIMESTAMPTZ -> value.isTextual();
        };
    if (!validJsonType) {
      throw new IllegalArgumentException("Unexpected JSON value type for column " + name);
    }
    return switch (type) {
      case BOOLEAN -> BooleanColumn.of(name, value.booleanValue());
      case INT -> IntColumn.of(name, value.intValue());
      case BIGINT -> BigIntColumn.of(name, value.longValue());
      case FLOAT -> FloatColumn.of(name, Float.parseFloat(value.textValue()));
      case DOUBLE -> DoubleColumn.of(name, Double.parseDouble(value.textValue()));
      case TEXT -> TextColumn.of(name, value.textValue());
      case BLOB -> BlobColumn.of(name, Base64.getDecoder().decode(value.textValue()));
      case DATE -> DateColumn.of(name, LocalDate.parse(value.textValue()));
      case TIME -> TimeColumn.of(name, LocalTime.parse(value.textValue()));
      case TIMESTAMP -> TimestampColumn.of(name, LocalDateTime.parse(value.textValue()));
      case TIMESTAMPTZ -> TimestampTZColumn.of(name, Instant.parse(value.textValue()));
    };
  }
}
