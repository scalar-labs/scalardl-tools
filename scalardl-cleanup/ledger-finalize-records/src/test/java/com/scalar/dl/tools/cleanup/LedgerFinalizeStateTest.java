package com.scalar.dl.tools.cleanup;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scalar.dl.tools.cleanup.LedgerFinalizeState.TargetTable;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;

class LedgerFinalizeStateTest {

  private static final ObjectMapper mapper = new ObjectMapper();

  private static final TargetTable T1 = new TargetTable("s1", "ns.t1");
  private static final TargetTable T2 = new TargetTable("s1", "ns.t2");

  @Test
  void constructor_shouldInitializeFields() {
    // Arrange & Act
    LedgerFinalizeState state =
        new LedgerFinalizeState(1000L, Arrays.asList(T1, T2), Collections.emptyList());

    // Assert
    assertThat(state.getStartedAtMs()).isEqualTo(1000L);
    assertThat(state.getTableList()).containsExactly(T1, T2);
    assertThat(state.getCompletedTables()).isEmpty();
  }

  @Test
  void constructor_nullListsGiven_shouldDefaultToEmptyLists() {
    // Arrange & Act
    LedgerFinalizeState state = new LedgerFinalizeState(1000L, null, null);

    // Assert
    assertThat(state.getTableList()).isEmpty();
    assertThat(state.getCompletedTables()).isEmpty();
  }

  @Test
  void markTableCompleted_shouldAddToCompletedTables() {
    // Arrange
    LedgerFinalizeState state =
        new LedgerFinalizeState(1000L, Arrays.asList(T1, T2), Collections.emptyList());

    // Act
    state.markTableCompleted(T1);

    // Assert
    assertThat(state.getCompletedTables()).containsExactly(T1);
  }

  @Test
  void markTableCompleted_duplicateGiven_shouldNotAddTwice() {
    // Arrange
    LedgerFinalizeState state =
        new LedgerFinalizeState(1000L, Collections.singletonList(T1), Collections.emptyList());

    // Act
    state.markTableCompleted(T1);
    state.markTableCompleted(T1);

    // Assert
    assertThat(state.getCompletedTables()).containsExactly(T1);
  }

  @Test
  void markTableCompleted_sameNameInAnotherStorageGiven_shouldNotMarkIt() {
    // Arrange
    TargetTable sameNameInAnotherStorage = new TargetTable("s2", "ns.t1");
    LedgerFinalizeState state =
        new LedgerFinalizeState(
            1000L, Arrays.asList(T1, sameNameInAnotherStorage), Collections.emptyList());

    // Act
    state.markTableCompleted(T1);

    // Assert
    assertThat(state.getCompletedTables()).containsExactly(T1);
  }

  @Test
  void jsonRoundTrip_shouldPreserveAllFields() throws Exception {
    // Arrange
    LedgerFinalizeState original =
        new LedgerFinalizeState(
            1745000000000L, Arrays.asList(T1, T2), Collections.singletonList(T1));

    // Act
    String json = mapper.writeValueAsString(original);
    LedgerFinalizeState deserialized = mapper.readValue(json, LedgerFinalizeState.class);

    // Assert
    assertThat(deserialized.getStartedAtMs()).isEqualTo(original.getStartedAtMs());
    assertThat(deserialized.getTableList()).isEqualTo(original.getTableList());
    assertThat(deserialized.getCompletedTables()).isEqualTo(original.getCompletedTables());
  }

  @Test
  void jsonSerialization_shouldUseSnakeCaseFieldNames() throws Exception {
    // Arrange
    LedgerFinalizeState state =
        new LedgerFinalizeState(
            1000L, Collections.singletonList(T1), Collections.singletonList(T1));

    // Act
    JsonNode json = mapper.valueToTree(state);

    // Assert
    assertThat(json.fieldNames())
        .toIterable()
        .containsExactlyInAnyOrder("started_at_ms", "table_list", "completed_tables");
    JsonNode table = json.get("table_list").get(0);
    assertThat(table.fieldNames()).toIterable().containsExactlyInAnyOrder("storage_id", "table");
    assertThat(table.get("storage_id").asText()).isEqualTo("s1");
    assertThat(table.get("table").asText()).isEqualTo("ns.t1");
  }
}
