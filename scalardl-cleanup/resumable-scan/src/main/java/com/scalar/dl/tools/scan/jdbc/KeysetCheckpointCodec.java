package com.scalar.dl.tools.scan.jdbc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Serializes and deserializes a keyset checkpoint: the primary-key values of the last record whose
 * page was fully processed.
 *
 * <p>The JSON carries a format version and, for each primary-key column, its name, ScalarDB data
 * type, and value. Decoding validates all three against the current table metadata so that a
 * checkpoint written for a different schema is rejected instead of silently resuming from a
 * meaningless position.
 *
 * <p>Values are encoded per type so that no precision is lost: BIGINT as a JSON integer read back
 * as a {@code long}, BLOB as base64, and the date/time types as ISO-8601 strings.
 */
final class KeysetCheckpointCodec {

  static final int FORMAT_VERSION = 1;

  private static final String FIELD_VERSION = "version";
  private static final String FIELD_COLUMNS = "columns";
  private static final String FIELD_NAME = "name";
  private static final String FIELD_TYPE = "type";
  private static final String FIELD_VALUE = "value";

  private static final ObjectMapper mapper = new ObjectMapper();

  private KeysetCheckpointCodec() {}

  /**
   * Encodes the given primary-key columns as a checkpoint JSON string.
   *
   * @param keyColumns the primary-key columns of the last fully processed record, in primary-key
   *     order
   * @return the checkpoint JSON
   */
  static String encode(List<Column<?>> keyColumns) {
    ObjectNode root = mapper.createObjectNode();
    root.put(FIELD_VERSION, FORMAT_VERSION);
    ArrayNode columns = root.putArray(FIELD_COLUMNS);
    for (Column<?> column : keyColumns) {
      ObjectNode node = columns.addObject();
      node.put(FIELD_NAME, column.getName());
      node.put(FIELD_TYPE, column.getDataType().name());
      putValue(node, column);
    }
    try {
      return mapper.writeValueAsString(root);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to encode the keyset checkpoint", e);
    }
  }

  /**
   * Decodes a checkpoint JSON string, validating it against the expected primary-key columns.
   *
   * @param json the checkpoint JSON
   * @param expectedNames the primary-key column names of the table, in primary-key order
   * @param expectedTypes the data type of each expected column, in the same order
   * @return the decoded primary-key columns, in primary-key order
   * @throws CheckpointMismatchException if the checkpoint cannot be applied to the table
   */
  static List<Column<?>> decode(
      String json, List<String> expectedNames, List<DataType> expectedTypes) {
    JsonNode root;
    try {
      root = mapper.readTree(json);
    } catch (Exception e) {
      throw new CheckpointMismatchException("The keyset checkpoint is not valid JSON", e);
    }

    JsonNode version = root.get(FIELD_VERSION);
    if (version == null || !version.isInt() || version.intValue() != FORMAT_VERSION) {
      throw new CheckpointMismatchException(
          "The keyset checkpoint format version is not supported: "
              + (version == null ? "missing" : version.asText())
              + " (expected "
              + FORMAT_VERSION
              + ")");
    }

    JsonNode columns = root.get(FIELD_COLUMNS);
    if (columns == null || !columns.isArray()) {
      throw new CheckpointMismatchException("The keyset checkpoint has no columns array");
    }
    if (columns.size() != expectedNames.size()) {
      throw new CheckpointMismatchException(
          "The keyset checkpoint has "
              + columns.size()
              + " primary-key column(s) but the table has "
              + expectedNames.size());
    }

    List<Column<?>> decoded = new ArrayList<>();
    for (int i = 0; i < columns.size(); i++) {
      JsonNode node = columns.get(i);
      String expectedName = expectedNames.get(i);
      DataType expectedType = expectedTypes.get(i);

      JsonNode name = node.get(FIELD_NAME);
      if (name == null || !expectedName.equals(name.asText())) {
        throw new CheckpointMismatchException(
            "The keyset checkpoint primary-key column at position "
                + i
                + " is '"
                + (name == null ? "missing" : name.asText())
                + "' but the table has '"
                + expectedName
                + "'");
      }

      JsonNode type = node.get(FIELD_TYPE);
      if (type == null || !expectedType.name().equals(type.asText())) {
        throw new CheckpointMismatchException(
            "The keyset checkpoint column '"
                + expectedName
                + "' has type "
                + (type == null ? "missing" : type.asText())
                + " but the table has "
                + expectedType.name());
      }

      JsonNode value = node.get(FIELD_VALUE);
      if (value == null || value.isNull()) {
        throw new CheckpointMismatchException(
            "The keyset checkpoint column '" + expectedName + "' has no value");
      }
      decoded.add(toColumn(expectedName, expectedType, value));
    }
    return decoded;
  }

  private static void putValue(ObjectNode node, Column<?> column) {
    if (column.hasNullValue()) {
      throw new IllegalArgumentException(
          "A primary-key column must not be null: " + column.getName());
    }
    switch (column.getDataType()) {
      case BOOLEAN:
        node.put(FIELD_VALUE, column.getBooleanValue());
        break;
      case INT:
        node.put(FIELD_VALUE, column.getIntValue());
        break;
      case BIGINT:
        node.put(FIELD_VALUE, column.getBigIntValue());
        break;
      case FLOAT:
        {
          float value = column.getFloatValue();
          // NaN and the infinities have no JSON number form. Write them as their textual forms
          // rather than relying on Jackson's non-numeric quoting, which is a configurable feature.
          if (Float.isFinite(value)) {
            node.put(FIELD_VALUE, value);
          } else {
            node.put(FIELD_VALUE, Float.toString(value));
          }
          break;
        }
      case DOUBLE:
        {
          double value = column.getDoubleValue();
          if (Double.isFinite(value)) {
            node.put(FIELD_VALUE, value);
          } else {
            node.put(FIELD_VALUE, Double.toString(value));
          }
          break;
        }
      case TEXT:
        node.put(FIELD_VALUE, column.getTextValue());
        break;
      case BLOB:
        node.put(FIELD_VALUE, Base64.getEncoder().encodeToString(column.getBlobValueAsBytes()));
        break;
      case DATE:
        node.put(FIELD_VALUE, column.getDateValue().toString());
        break;
      case TIME:
        node.put(FIELD_VALUE, column.getTimeValue().toString());
        break;
      case TIMESTAMP:
        node.put(FIELD_VALUE, column.getTimestampValue().toString());
        break;
      case TIMESTAMPTZ:
        node.put(FIELD_VALUE, column.getTimestampTZValue().toString());
        break;
      default:
        throw new IllegalArgumentException(
            "Unsupported primary-key column type: " + column.getDataType());
    }
  }

  private static Column<?> toColumn(String name, DataType type, JsonNode value) {
    try {
      switch (type) {
        case BOOLEAN:
          requireNodeType(name, type, value.isBoolean());
          return BooleanColumn.of(name, value.booleanValue());
        case INT:
          requireNodeType(name, type, value.isIntegralNumber() && value.canConvertToInt());
          return IntColumn.of(name, value.intValue());
        case BIGINT:
          requireNodeType(name, type, value.isIntegralNumber() && value.canConvertToLong());
          return BigIntColumn.of(name, value.longValue());
        case FLOAT:
          return FloatColumn.of(
              name,
              value.isNumber()
                  ? value.floatValue()
                  : Float.parseFloat(nonFinite(name, type, value)));
        case DOUBLE:
          return DoubleColumn.of(
              name,
              value.isNumber()
                  ? value.doubleValue()
                  : Double.parseDouble(nonFinite(name, type, value)));
        case TEXT:
          return TextColumn.of(name, requireText(name, type, value));
        case BLOB:
          return BlobColumn.of(name, Base64.getDecoder().decode(requireText(name, type, value)));
        case DATE:
          return DateColumn.of(name, LocalDate.parse(requireText(name, type, value)));
        case TIME:
          return TimeColumn.of(name, LocalTime.parse(requireText(name, type, value)));
        case TIMESTAMP:
          return TimestampColumn.of(name, LocalDateTime.parse(requireText(name, type, value)));
        case TIMESTAMPTZ:
          return TimestampTZColumn.of(name, Instant.parse(requireText(name, type, value)));
        default:
          throw new CheckpointMismatchException("Unsupported primary-key column type: " + type);
      }
    } catch (IllegalArgumentException | DateTimeParseException e) {
      throw new CheckpointMismatchException(
          "The keyset checkpoint value for column '" + name + "' is not a valid " + type, e);
    }
  }

  /**
   * Returns the node's text, rejecting a non-textual node. Without this check a wrongly-typed value
   * would read back as null or as a silent zero, and the scan would resume from a position the
   * checkpoint never recorded.
   */
  private static String requireText(String name, DataType type, JsonNode value) {
    requireNodeType(name, type, value.isTextual());
    return value.textValue();
  }

  /**
   * Returns the text of a non-numeric floating-point value ("NaN", "Infinity", "-Infinity").
   * Parsing rejects anything else, which the caller turns into a checkpoint mismatch.
   */
  private static String nonFinite(String name, DataType type, JsonNode value) {
    requireNodeType(name, type, value.isTextual());
    return value.textValue();
  }

  private static void requireNodeType(String name, DataType type, boolean valid) {
    if (!valid) {
      throw new CheckpointMismatchException(
          "The keyset checkpoint value for column '" + name + "' is not a valid " + type);
    }
  }
}
