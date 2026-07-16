package com.scalar.dl.tools.cleanup;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;

class LedgerFinalizeStateTest {

  private static final ObjectMapper mapper = new ObjectMapper();

  @Test
  void constructor_shouldInitializeFields() {
    // Arrange & Act
    LedgerFinalizeState state =
        new LedgerFinalizeState(1000L, Arrays.asList("ns.t1", "ns.t2"), Collections.emptyList());

    // Assert
    assertThat(state.getStartedAtMs()).isEqualTo(1000L);
    assertThat(state.getTableList()).containsExactly("ns.t1", "ns.t2");
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
        new LedgerFinalizeState(1000L, Arrays.asList("ns.t1", "ns.t2"), Collections.emptyList());

    // Act
    state.markTableCompleted("ns.t1");

    // Assert
    assertThat(state.getCompletedTables()).containsExactly("ns.t1");
  }

  @Test
  void markTableCompleted_duplicateGiven_shouldNotAddTwice() {
    // Arrange
    LedgerFinalizeState state =
        new LedgerFinalizeState(1000L, Collections.singletonList("ns.t1"), Collections.emptyList());

    // Act
    state.markTableCompleted("ns.t1");
    state.markTableCompleted("ns.t1");

    // Assert
    assertThat(state.getCompletedTables()).containsExactly("ns.t1");
  }

  @Test
  void jsonRoundTrip_shouldPreserveAllFields() throws Exception {
    // Arrange
    LedgerFinalizeState original =
        new LedgerFinalizeState(
            1745000000000L, Arrays.asList("ns.t1", "ns.t2"), Collections.singletonList("ns.t1"));

    // Act
    String json = mapper.writeValueAsString(original);
    LedgerFinalizeState deserialized = mapper.readValue(json, LedgerFinalizeState.class);

    // Assert
    assertThat(deserialized.getStartedAtMs()).isEqualTo(original.getStartedAtMs());
    assertThat(deserialized.getTableList()).isEqualTo(original.getTableList());
    assertThat(deserialized.getCompletedTables()).isEqualTo(original.getCompletedTables());
  }
}
