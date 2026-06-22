package com.scalar.dl.tools.cleanup;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;

class AuditorFinalizeStateTest {

  private static final ObjectMapper mapper = new ObjectMapper();

  @Test
  void constructor_shouldInitializeFields() {
    // Arrange & Act
    AuditorFinalizeState state = new AuditorFinalizeState(1000L, Arrays.asList("default", "ns1"));

    // Assert
    assertThat(state.getStartedAtMs()).isEqualTo(1000L);
    assertThat(state.getNamespaceList()).containsExactly("default", "ns1");
    assertThat(state.getCompletedNamespaces()).isEmpty();
  }

  @Test
  void constructor_nullListsGiven_shouldDefaultToEmptyLists() {
    // Arrange & Act
    AuditorFinalizeState state = new AuditorFinalizeState(1000L, null, null);

    // Assert
    assertThat(state.getNamespaceList()).isEmpty();
    assertThat(state.getCompletedNamespaces()).isEmpty();
  }

  @Test
  void markNamespaceCompleted_shouldAddToCompletedNamespaces() {
    // Arrange
    AuditorFinalizeState state =
        new AuditorFinalizeState(1000L, Collections.singletonList("default"));

    // Act
    state.markNamespaceCompleted("default");

    // Assert
    assertThat(state.getCompletedNamespaces()).containsExactly("default");
  }

  @Test
  void markNamespaceCompleted_duplicateGiven_shouldNotAddTwice() {
    // Arrange
    AuditorFinalizeState state =
        new AuditorFinalizeState(1000L, Collections.singletonList("default"));

    // Act
    state.markNamespaceCompleted("default");
    state.markNamespaceCompleted("default");

    // Assert
    assertThat(state.getCompletedNamespaces()).containsExactly("default");
  }

  @Test
  void jsonRoundTrip_shouldPreserveAllFields() throws Exception {
    // Arrange
    AuditorFinalizeState original =
        new AuditorFinalizeState(
            1745000000000L, Arrays.asList("default", "ns1"), Collections.singletonList("default"));

    // Act
    String json = mapper.writeValueAsString(original);
    AuditorFinalizeState deserialized = mapper.readValue(json, AuditorFinalizeState.class);

    // Assert
    assertThat(deserialized.getStartedAtMs()).isEqualTo(original.getStartedAtMs());
    assertThat(deserialized.getNamespaceList()).containsExactly("default", "ns1");
    assertThat(deserialized.getCompletedNamespaces()).containsExactly("default");
  }
}
