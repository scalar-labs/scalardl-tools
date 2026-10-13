package com.scalar.dl.tools.scan.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scalar.db.api.AndConditionSet;
import com.scalar.db.api.ConditionalExpression;
import com.scalar.db.api.ConditionalExpression.Operator;
import com.scalar.db.api.Scan;
import com.scalar.db.api.Scan.Ordering;
import com.scalar.db.api.Selection.Conjunction;
import com.scalar.db.api.TableMetadata;
import com.scalar.db.io.BlobColumn;
import com.scalar.db.io.Column;
import com.scalar.db.io.DataType;
import com.scalar.db.io.DateColumn;
import com.scalar.db.io.IntColumn;
import com.scalar.db.io.TextColumn;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class KeysetConditionBuilderTest {

  private static TableMetadata singleKeyMetadata() {
    return TableMetadata.newBuilder()
        .addColumn("id", DataType.TEXT)
        .addColumn("value", DataType.TEXT)
        .addPartitionKey("id")
        .build();
  }

  private static TableMetadata compositeKeyMetadata() {
    return TableMetadata.newBuilder()
        .addColumn("id", DataType.TEXT)
        .addColumn("age", DataType.INT)
        .addColumn("value", DataType.TEXT)
        .addPartitionKey("id")
        .addClusteringKey("age")
        .build();
  }

  private static TableMetadata threeColumnKeyMetadata() {
    return TableMetadata.newBuilder()
        .addColumn("a", DataType.TEXT)
        .addColumn("b", DataType.INT)
        .addColumn("c", DataType.INT)
        .addColumn("value", DataType.TEXT)
        .addPartitionKey("a")
        .addClusteringKey("b")
        .addClusteringKey("c")
        .build();
  }

  private static TableMetadata descClusteringKeyMetadata() {
    return TableMetadata.newBuilder()
        .addColumn("id", DataType.TEXT)
        .addColumn("age", DataType.INT)
        .addColumn("value", DataType.TEXT)
        .addPartitionKey("id")
        .addClusteringKey("age", Scan.Ordering.Order.DESC)
        .build();
  }

  /** Flattens each disjunct into its (column, operator) pairs, in order. */
  private static List<List<String>> disjunctsOf(Set<AndConditionSet> disjuncts) {
    List<List<String>> result = new ArrayList<>();
    for (AndConditionSet disjunct : disjuncts) {
      List<String> conditions = new ArrayList<>();
      for (ConditionalExpression expression : disjunct.getConditions()) {
        conditions.add(expression.getColumn().getName() + " " + expression.getOperator());
      }
      result.add(conditions);
    }
    return result;
  }

  @Test
  void primaryKeyColumnNames_compositeKeyGiven_shouldReturnPartitionKeysThenClusteringKeys() {
    // Arrange
    TableMetadata metadata = threeColumnKeyMetadata();

    // Act
    List<String> names = KeysetConditionBuilder.primaryKeyColumnNames(metadata);

    // Assert
    assertThat(names).containsExactly("a", "b", "c");
  }

  @Test
  void orderings_allAscendingTableGiven_shouldOrderEveryKeyColumnAscending() {
    // Arrange
    TableMetadata metadata = compositeKeyMetadata();

    // Act
    List<Ordering> orderings = KeysetConditionBuilder.orderings(metadata);

    // Assert
    assertThat(orderings).containsExactly(Ordering.asc("id"), Ordering.asc("age"));
  }

  @Test
  void orderings_descClusteringOrderGiven_shouldOrderThatColumnDescending() {
    // Arrange
    TableMetadata metadata = descClusteringKeyMetadata();

    // Act
    List<Ordering> orderings = KeysetConditionBuilder.orderings(metadata);

    // Assert
    assertThat(orderings).containsExactly(Ordering.asc("id"), Ordering.desc("age"));
  }

  @Test
  void resumeCondition_singleKeyGiven_shouldProduceOneStrictlyGreaterCondition() {
    // Arrange
    TableMetadata metadata = singleKeyMetadata();
    List<Column<?>> key = Collections.singletonList(TextColumn.of("id", "asset-1"));

    // Act
    Set<AndConditionSet> disjuncts = KeysetConditionBuilder.resumeCondition(metadata, key);

    // Assert
    assertThat(disjunctsOf(disjuncts)).containsExactly(Collections.singletonList("id GT"));
  }

  @Test
  void resumeCondition_twoColumnKeyGiven_shouldMatchCheckpointPrefixInTheSecondDisjunct() {
    // Arrange
    TableMetadata metadata = compositeKeyMetadata();
    List<Column<?>> key = Arrays.asList(TextColumn.of("id", "asset-1"), IntColumn.of("age", 7));

    // Act
    Set<AndConditionSet> disjuncts = KeysetConditionBuilder.resumeCondition(metadata, key);

    // Assert
    assertThat(disjunctsOf(disjuncts))
        .containsExactly(
            Arrays.asList("id GTE", "id GT"), Arrays.asList("id GTE", "id EQ", "age GT"));
  }

  @Test
  void resumeCondition_threeColumnKeyGiven_shouldEmitOneDisjunctPerKeyColumn() {
    // Arrange
    TableMetadata metadata = threeColumnKeyMetadata();
    List<Column<?>> key =
        Arrays.asList(TextColumn.of("a", "x"), IntColumn.of("b", 2), IntColumn.of("c", 3));

    // Act
    Set<AndConditionSet> disjuncts = KeysetConditionBuilder.resumeCondition(metadata, key);

    // Assert
    // Disjunct k matches the checkpoint on the first k-1 columns and is past it on column k, so
    // the records whose leading columns equal the checkpoint are admitted by a later disjunct.
    // Every disjunct carries the leading "a GTE" bound; see the expansion test below.
    assertThat(disjunctsOf(disjuncts))
        .containsExactly(
            Arrays.asList("a GTE", "a GT"),
            Arrays.asList("a GTE", "a EQ", "b GT"),
            Arrays.asList("a GTE", "a EQ", "b EQ", "c GT"));
  }

  @Test
  void resumeCondition_descClusteringColumnGiven_shouldFlipThatColumnsComparisons() {
    // Arrange
    TableMetadata metadata = descClusteringKeyMetadata();
    List<Column<?>> key = Arrays.asList(TextColumn.of("id", "asset-1"), IntColumn.of("age", 7));

    // Act
    Set<AndConditionSet> disjuncts = KeysetConditionBuilder.resumeCondition(metadata, key);

    // Assert
    assertThat(disjunctsOf(disjuncts))
        .containsExactly(
            Arrays.asList("id GTE", "id GT"), Arrays.asList("id GTE", "id EQ", "age LT"));
  }

  @Test
  void resumeCondition_blobAndDateKeysGiven_shouldBuildConditionsForEveryKeyType() {
    // Arrange
    TableMetadata metadata =
        TableMetadata.newBuilder()
            .addColumn("payload", DataType.BLOB)
            .addColumn("d", DataType.DATE)
            .addColumn("value", DataType.TEXT)
            .addPartitionKey("payload")
            .addClusteringKey("d")
            .build();
    List<Column<?>> key =
        Arrays.asList(
            BlobColumn.of("payload", "k".getBytes(StandardCharsets.UTF_8)),
            DateColumn.of("d", LocalDate.of(2026, 8, 21)));

    // Act
    Set<AndConditionSet> disjuncts = KeysetConditionBuilder.resumeCondition(metadata, key);

    // Assert
    assertThat(disjunctsOf(disjuncts))
        .containsExactly(
            Arrays.asList("payload GTE", "payload GT"),
            Arrays.asList("payload GTE", "payload EQ", "d GT"));
  }

  @Test
  void resumeCondition_emptyKeyGiven_shouldThrowIllegalArgumentException() {
    // Arrange
    TableMetadata metadata = singleKeyMetadata();

    // Act & Assert
    assertThatThrownBy(
            () -> KeysetConditionBuilder.resumeCondition(metadata, Collections.emptyList()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void resumeCondition_whenBuiltIntoScan_shouldKeepSharedLowerBoundInEveryDisjunct() {
    // Arrange
    // Every disjunct must carry the leading column's lower bound: that shared bound is what lets
    // PostgreSQL start the index scan at the checkpoint instead of filtering from the beginning of
    // the index. Measured on a 500k-row table, dropping it costs ~135x more buffers per page.
    TableMetadata metadata = threeColumnKeyMetadata();
    List<Column<?>> key =
        Arrays.asList(TextColumn.of("a", "x"), IntColumn.of("b", 2), IntColumn.of("c", 3));

    // Act
    Set<Conjunction> conjunctions = scanOf(metadata, key).getConjunctions();

    // Assert
    for (Conjunction conjunction : conjunctions) {
      assertThat(conjunction.getConditions())
          .anySatisfy(
              condition -> {
                assertThat(condition.getColumn().getName()).isEqualTo("a");
                assertThat(condition.getOperator()).isIn(Operator.GT, Operator.GTE);
              });
    }
  }

  @Test
  void resumeCondition_whenBuiltIntoScan_shouldStayLinearInTheNumberOfKeyColumns() {
    // Arrange
    // The condition is disjunctive so ScalarDB passes it through untouched. An equivalent
    // conjunctive condition would be expanded by cartesian product instead, reaching ~4,000
    // disjuncts and ~28,000 bound parameters at eight key columns.
    int keyColumnCount = 8;
    TableMetadata.Builder builder = TableMetadata.newBuilder().addColumn("value", DataType.TEXT);
    List<Column<?>> key = new ArrayList<>();
    for (int i = 0; i < keyColumnCount; i++) {
      String name = "c" + i;
      builder.addColumn(name, DataType.INT);
      if (i == 0) {
        builder.addPartitionKey(name);
      } else {
        builder.addClusteringKey(name);
      }
      key.add(IntColumn.of(name, i));
    }

    // Act
    Set<Conjunction> conjunctions = scanOf(builder.build(), key).getConjunctions();

    // Assert
    assertThat(conjunctions).hasSize(keyColumnCount);
    int conditionCount = conjunctions.stream().mapToInt(c -> c.getConditions().size()).sum();
    assertThat(conditionCount).isLessThanOrEqualTo(keyColumnCount * (keyColumnCount + 1));
  }

  private static Scan scanOf(TableMetadata metadata, List<Column<?>> key) {
    return Scan.newBuilder()
        .namespace("ns")
        .table("tbl")
        .all()
        .whereOr(KeysetConditionBuilder.resumeCondition(metadata, key))
        .orderings(KeysetConditionBuilder.orderings(metadata))
        .limit(10)
        .build();
  }
}
