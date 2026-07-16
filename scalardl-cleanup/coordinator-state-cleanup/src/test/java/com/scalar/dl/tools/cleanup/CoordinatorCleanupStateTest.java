package com.scalar.dl.tools.cleanup;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class CoordinatorCleanupStateTest {

  private static final ObjectMapper mapper = new ObjectMapper();

  @Test
  void constructor_shouldInitializeFieldsAndDefaultToNotCompleted() {
    // Arrange & Act
    CoordinatorCleanupState state = new CoordinatorCleanupState(1000L);

    // Assert
    assertThat(state.getDeletableBeforeMs()).isEqualTo(1000L);
    assertThat(state.isCompleted()).isFalse();
  }

  @Test
  void markCompleted_shouldSetCompletedToTrue() {
    // Arrange
    CoordinatorCleanupState state = new CoordinatorCleanupState(1000L);

    // Act
    state.markCompleted();

    // Assert
    assertThat(state.isCompleted()).isTrue();
  }

  @Test
  void jsonRoundTrip_shouldPreserveAllFields() throws Exception {
    // Arrange
    CoordinatorCleanupState original = new CoordinatorCleanupState(1745000000000L);
    original.markCompleted();

    // Act
    String json = mapper.writeValueAsString(original);
    CoordinatorCleanupState deserialized = mapper.readValue(json, CoordinatorCleanupState.class);

    // Assert
    assertThat(deserialized.getDeletableBeforeMs()).isEqualTo(original.getDeletableBeforeMs());
    assertThat(deserialized.isCompleted()).isEqualTo(original.isCompleted());
  }

  @Test
  void deserialize_legacyJsonWithoutCompletedGiven_shouldDefaultToNotCompleted() throws Exception {
    // Arrange
    String legacyJson = "{\"deletable_before_ms\":1745000000000}";

    // Act
    CoordinatorCleanupState deserialized =
        mapper.readValue(legacyJson, CoordinatorCleanupState.class);

    // Assert
    assertThat(deserialized.getDeletableBeforeMs()).isEqualTo(1745000000000L);
    assertThat(deserialized.isCompleted()).isFalse();
  }
}
