package com.scalar.dl.tools.cleanup;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class CoordinatorCleanupStateTest {

  private static final ObjectMapper mapper = new ObjectMapper();

  @Test
  void constructor_shouldInitializeField() {
    // Arrange & Act
    CoordinatorCleanupState state = new CoordinatorCleanupState(1000L);

    // Assert
    assertThat(state.getDeletableBeforeMs()).isEqualTo(1000L);
  }

  @Test
  void jsonRoundTrip_shouldPreserveAllFields() throws Exception {
    // Arrange
    CoordinatorCleanupState original = new CoordinatorCleanupState(1745000000000L);

    // Act
    String json = mapper.writeValueAsString(original);
    CoordinatorCleanupState deserialized = mapper.readValue(json, CoordinatorCleanupState.class);

    // Assert
    assertThat(deserialized.getDeletableBeforeMs()).isEqualTo(original.getDeletableBeforeMs());
  }
}
