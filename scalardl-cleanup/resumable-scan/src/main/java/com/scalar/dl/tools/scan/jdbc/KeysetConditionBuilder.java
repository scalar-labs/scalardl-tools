package com.scalar.dl.tools.scan.jdbc;

import com.google.common.collect.ImmutableSet;
import com.scalar.db.api.AndConditionSet;
import com.scalar.db.api.ConditionBuilder;
import com.scalar.db.api.ConditionBuilder.ConditionalExpressionBuilder;
import com.scalar.db.api.ConditionSetBuilder;
import com.scalar.db.api.ConditionalExpression;
import com.scalar.db.api.Scan;
import com.scalar.db.api.Scan.Ordering;
import com.scalar.db.api.TableMetadata;
import com.scalar.db.io.Column;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds the orderings and keyset resume condition for a page of a JDBC cross-partition scan.
 *
 * <p>The orderings cover every primary-key column (partition keys first, then clustering keys) in
 * the direction the table declares, so a page boundary follows the same total order the resume
 * condition assumes.
 *
 * <p>The resume condition selects the records that come strictly after the checkpointed key in that
 * order. For primary-key columns {@code c1..cn} it is expressed in disjunctive normal form, one
 * disjunct per column:
 *
 * <pre>
 *   (c1 &gt;= v1 AND c1 &gt; v1)
 *   OR (c1 &gt;= v1 AND c1 = v1 AND c2 &gt; v2)
 *   OR (c1 &gt;= v1 AND c1 = v1 AND c2 = v2 AND c3 &gt; v3)
 *   OR …
 * </pre>
 *
 * <p>Disjunct k admits the records that match the checkpoint on the first k-1 columns and are past
 * it on column k, so the disjuncts together admit exactly the records after the checkpoint.
 *
 * <p>The leading {@code c1 >= v1} is redundant in every disjunct, and deliberately so: it is the
 * only bound the disjuncts share literally, and it is what lets PostgreSQL start the index scan at
 * the checkpoint instead of filtering from the start of the index. Without it a resumed scan
 * re-reads every record before the checkpoint on each page, which is quadratic over a full scan.
 * MySQL derives a tight range per disjunct on its own.
 *
 * <p>This is built as disjunctive normal form on purpose. The equivalent conjunctive form is
 * expanded by ScalarDB into disjunctive normal form by cartesian product, which grows
 * super-exponentially in the number of primary-key columns (a 8-column key reaches ~4,000 disjuncts
 * and ~28,000 bound parameters); the form below stays linear in disjuncts.
 *
 * <p>A column whose clustering order is DESC has its final comparison flipped, matching the
 * descending direction of its index.
 */
final class KeysetConditionBuilder {

  /** The comparison to apply to one key column. */
  private enum Op {
    EQ,
    GT,
    GTE,
    LT
  }

  private KeysetConditionBuilder() {}

  /**
   * Returns the primary-key column names of the table, partition keys first, then clustering keys.
   */
  static List<String> primaryKeyColumnNames(TableMetadata metadata) {
    List<String> names = new ArrayList<>(metadata.getPartitionKeyNames());
    names.addAll(metadata.getClusteringKeyNames());
    return names;
  }

  /** Returns the orderings covering every primary-key column in the table's declared direction. */
  static List<Ordering> orderings(TableMetadata metadata) {
    List<Ordering> orderings = new ArrayList<>();
    for (String name : metadata.getPartitionKeyNames()) {
      orderings.add(Ordering.asc(name));
    }
    for (String name : metadata.getClusteringKeyNames()) {
      orderings.add(isDescending(metadata, name) ? Ordering.desc(name) : Ordering.asc(name));
    }
    return orderings;
  }

  /**
   * Builds the disjunctive resume condition for the records that follow the checkpointed key.
   *
   * @param metadata the table metadata, used for the clustering order of each column
   * @param keyColumns the checkpointed primary-key columns, in primary-key order
   * @return one {@link AndConditionSet} per disjunct, to be passed to {@code whereOr}
   */
  static Set<AndConditionSet> resumeCondition(TableMetadata metadata, List<Column<?>> keyColumns) {
    if (keyColumns.isEmpty()) {
      throw new IllegalArgumentException("keyColumns must not be empty");
    }

    Column<?> leading = keyColumns.get(0);
    // A single-column key needs no shared bound: its one disjunct is already a plain range.
    if (keyColumns.size() == 1) {
      return ImmutableSet.of(
          ConditionSetBuilder.andConditionSet(ImmutableSet.of(past(metadata, leading))).build());
    }

    Set<AndConditionSet> disjuncts = new LinkedHashSet<>();
    for (int k = 0; k < keyColumns.size(); k++) {
      Set<ConditionalExpression> conditions = new LinkedHashSet<>();
      // The shared bound that keeps the index scan starting at the checkpoint. The leading column
      // is a partition key, which is always ascending.
      conditions.add(comparison(leading, Op.GTE));
      for (int i = 0; i < k; i++) {
        conditions.add(comparison(keyColumns.get(i), Op.EQ));
      }
      conditions.add(past(metadata, keyColumns.get(k)));
      disjuncts.add(ConditionSetBuilder.andConditionSet(ImmutableSet.copyOf(conditions)).build());
    }
    return ImmutableSet.copyOf(disjuncts);
  }

  /** Builds the comparison admitting the values past the checkpoint in the column's direction. */
  private static ConditionalExpression past(TableMetadata metadata, Column<?> column) {
    return comparison(column, isDescending(metadata, column.getName()) ? Op.LT : Op.GT);
  }

  private static boolean isDescending(TableMetadata metadata, String columnName) {
    return metadata.getClusteringKeyNames().contains(columnName)
        && metadata.getClusteringOrder(columnName) == Scan.Ordering.Order.DESC;
  }

  /** Builds the comparison for one column, dispatching on its type. */
  private static ConditionalExpression comparison(Column<?> column, Op op) {
    if (column.hasNullValue()) {
      throw new IllegalArgumentException(
          "A primary-key column must not be null: " + column.getName());
    }
    ConditionalExpressionBuilder builder = ConditionBuilder.column(column.getName());

    switch (column.getDataType()) {
      case BOOLEAN:
        {
          boolean value = column.getBooleanValue();
          switch (op) {
            case EQ:
              return builder.isEqualToBoolean(value);
            case GT:
              return builder.isGreaterThanBoolean(value);
            case GTE:
              return builder.isGreaterThanOrEqualToBoolean(value);
            default:
              return builder.isLessThanBoolean(value);
          }
        }
      case INT:
        {
          int value = column.getIntValue();
          switch (op) {
            case EQ:
              return builder.isEqualToInt(value);
            case GT:
              return builder.isGreaterThanInt(value);
            case GTE:
              return builder.isGreaterThanOrEqualToInt(value);
            default:
              return builder.isLessThanInt(value);
          }
        }
      case BIGINT:
        {
          long value = column.getBigIntValue();
          switch (op) {
            case EQ:
              return builder.isEqualToBigInt(value);
            case GT:
              return builder.isGreaterThanBigInt(value);
            case GTE:
              return builder.isGreaterThanOrEqualToBigInt(value);
            default:
              return builder.isLessThanBigInt(value);
          }
        }
      case FLOAT:
        {
          float value = column.getFloatValue();
          switch (op) {
            case EQ:
              return builder.isEqualToFloat(value);
            case GT:
              return builder.isGreaterThanFloat(value);
            case GTE:
              return builder.isGreaterThanOrEqualToFloat(value);
            default:
              return builder.isLessThanFloat(value);
          }
        }
      case DOUBLE:
        {
          double value = column.getDoubleValue();
          switch (op) {
            case EQ:
              return builder.isEqualToDouble(value);
            case GT:
              return builder.isGreaterThanDouble(value);
            case GTE:
              return builder.isGreaterThanOrEqualToDouble(value);
            default:
              return builder.isLessThanDouble(value);
          }
        }
      case TEXT:
        {
          String value = column.getTextValue();
          switch (op) {
            case EQ:
              return builder.isEqualToText(value);
            case GT:
              return builder.isGreaterThanText(value);
            case GTE:
              return builder.isGreaterThanOrEqualToText(value);
            default:
              return builder.isLessThanText(value);
          }
        }
      case BLOB:
        {
          byte[] value = column.getBlobValueAsBytes();
          switch (op) {
            case EQ:
              return builder.isEqualToBlob(value);
            case GT:
              return builder.isGreaterThanBlob(value);
            case GTE:
              return builder.isGreaterThanOrEqualToBlob(value);
            default:
              return builder.isLessThanBlob(value);
          }
        }
      case DATE:
        {
          java.time.LocalDate value = column.getDateValue();
          switch (op) {
            case EQ:
              return builder.isEqualToDate(value);
            case GT:
              return builder.isGreaterThanDate(value);
            case GTE:
              return builder.isGreaterThanOrEqualToDate(value);
            default:
              return builder.isLessThanDate(value);
          }
        }
      case TIME:
        {
          java.time.LocalTime value = column.getTimeValue();
          switch (op) {
            case EQ:
              return builder.isEqualToTime(value);
            case GT:
              return builder.isGreaterThanTime(value);
            case GTE:
              return builder.isGreaterThanOrEqualToTime(value);
            default:
              return builder.isLessThanTime(value);
          }
        }
      case TIMESTAMP:
        {
          java.time.LocalDateTime value = column.getTimestampValue();
          switch (op) {
            case EQ:
              return builder.isEqualToTimestamp(value);
            case GT:
              return builder.isGreaterThanTimestamp(value);
            case GTE:
              return builder.isGreaterThanOrEqualToTimestamp(value);
            default:
              return builder.isLessThanTimestamp(value);
          }
        }
      case TIMESTAMPTZ:
        {
          java.time.Instant value = column.getTimestampTZValue();
          switch (op) {
            case EQ:
              return builder.isEqualToTimestampTZ(value);
            case GT:
              return builder.isGreaterThanTimestampTZ(value);
            case GTE:
              return builder.isGreaterThanOrEqualToTimestampTZ(value);
            default:
              return builder.isLessThanTimestampTZ(value);
          }
        }
      default:
        throw new IllegalArgumentException(
            "Unsupported primary-key column type: " + column.getDataType());
    }
  }
}
