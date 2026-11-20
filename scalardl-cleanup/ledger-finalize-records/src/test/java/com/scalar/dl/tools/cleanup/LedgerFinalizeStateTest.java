package com.scalar.dl.tools.cleanup;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LedgerFinalizeStateTest {

  private static final ObjectMapper mapper = new ObjectMapper();
  private static final Map<String, String> STORAGE_CONFIG =
      ImmutableMap.of("storage", "cosmos", "contact_points", "https://example.com");

  @Test
  void constructor_shouldInitializeFields() {
    // Arrange & Act
    LedgerFinalizeState state =
        new LedgerFinalizeState(1000L, Arrays.asList("ns.t1", "ns.t2"), STORAGE_CONFIG);

    // Assert
    assertThat(state.getStartedAtMs()).isEqualTo(1000L);
    assertThat(state.getTableList()).containsExactly("ns.t1", "ns.t2");
    assertThat(state.getCompletedTables()).isEmpty();
    assertThat(state.getStorageConfig()).isEqualTo(STORAGE_CONFIG);
  }

  @Test
  void constructor_nullsGiven_shouldDefaultToEmptyListsAndNullStorageConfig() {
    // Arrange & Act
    LedgerFinalizeState state = new LedgerFinalizeState(1000L, null, null, null);

    // Assert
    assertThat(state.getTableList()).isEmpty();
    assertThat(state.getCompletedTables()).isEmpty();
    assertThat(state.getStorageConfig()).isNull();
  }

  @Test
  void markTableCompleted_shouldAddToCompletedTables() {
    // Arrange
    LedgerFinalizeState state =
        new LedgerFinalizeState(1000L, Arrays.asList("ns.t1", "ns.t2"), STORAGE_CONFIG);

    // Act
    state.markTableCompleted("ns.t1");

    // Assert
    assertThat(state.getCompletedTables()).containsExactly("ns.t1");
  }

  @Test
  void markTableCompleted_duplicateGiven_shouldNotAddTwice() {
    // Arrange
    LedgerFinalizeState state =
        new LedgerFinalizeState(1000L, Collections.singletonList("ns.t1"), STORAGE_CONFIG);

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
            1745000000000L,
            Arrays.asList("ns.t1", "ns.t2"),
            Collections.singletonList("ns.t1"),
            STORAGE_CONFIG);

    // Act
    String json = mapper.writeValueAsString(original);
    LedgerFinalizeState deserialized = mapper.readValue(json, LedgerFinalizeState.class);

    // Assert
    assertThat(deserialized.getStartedAtMs()).isEqualTo(original.getStartedAtMs());
    assertThat(deserialized.getTableList()).isEqualTo(original.getTableList());
    assertThat(deserialized.getCompletedTables()).isEqualTo(original.getCompletedTables());
    assertThat(deserialized.getStorageConfig()).isEqualTo(STORAGE_CONFIG);
  }

  @Test
  void deserialize_jsonOfOlderVersionGiven_shouldHaveNullStorageConfig() throws Exception {
    // Arrange
    String json =
        "{\"started_at_ms\":1000,\"table_list\":[\"ns.t1\"],\"completed_tables\":[\"ns.t1\"]}";

    // Act
    LedgerFinalizeState state = mapper.readValue(json, LedgerFinalizeState.class);

    // Assert
    assertThat(state.getStartedAtMs()).isEqualTo(1000L);
    assertThat(state.getCompletedTables()).containsExactly("ns.t1");
    assertThat(state.getStorageConfig()).isNull();
  }
}
