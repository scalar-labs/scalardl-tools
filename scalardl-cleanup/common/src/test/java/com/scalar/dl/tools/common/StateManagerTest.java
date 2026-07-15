package com.scalar.dl.tools.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StateManagerTest {

  private static final String SUBDIRECTORY = "test-sub-dir";

  @TempDir Path tempDir;
  private TestStateManager manager;

  @BeforeEach
  void setUp() {
    manager = new TestStateManager(tempDir);
  }

  @Test
  void load_shouldReturnPersistedState() {
    // Arrange
    TestState state = new TestState("hello", 42);
    manager.persist(state);

    // Act
    TestState loaded = manager.load();

    // Assert
    assertThat(loaded).isNotNull();
    assertThat(loaded.name).isEqualTo("hello");
    assertThat(loaded.value).isEqualTo(42);
  }

  @Test
  void load_noStateFile_shouldReturnNull() {
    // Arrange

    // Act & Assert
    assertThat(manager.load()).isNull();
  }

  @Test
  void load_corruptedFileGiven_shouldThrowRuntimeException() throws IOException {
    // Arrange
    Path stateDir = tempDir.resolve(SUBDIRECTORY);
    Files.createDirectories(stateDir);
    Files.write(stateDir.resolve("state.json"), "not-json".getBytes(StandardCharsets.UTF_8));

    // Act & Assert
    assertThatThrownBy(() -> manager.load()).isInstanceOf(RuntimeException.class);
  }

  @Test
  void persist_shouldCreateNewStateFileIfNotExists() {
    // Act
    manager.persist(new TestState("x", 0));

    // Assert
    assertThat(tempDir.resolve(SUBDIRECTORY)).isDirectory();
    assertThat(tempDir.resolve(SUBDIRECTORY).resolve("state.json")).exists();
  }

  @Test
  void persist_shouldOverwriteExistingState() {
    // Arrange
    manager.persist(new TestState("first", 1));

    // Act
    manager.persist(new TestState("second", 2));
    TestState loaded = manager.load();

    // Assert
    assertThat(loaded).isNotNull();
    assertThat(loaded.name).isEqualTo("second");
    assertThat(loaded.value).isEqualTo(2);
  }

  @Test
  void getStateDir_shouldReturnCorrectPath() {
    // Arrange

    // Act & Assert
    assertThat(manager.getStateDir()).isEqualTo(tempDir.resolve(SUBDIRECTORY));
  }

  /** Minimal state POJO for testing. */
  static class TestState {
    @JsonProperty String name;
    @JsonProperty int value;

    @JsonCreator
    TestState(@JsonProperty("name") String name, @JsonProperty("value") int value) {
      this.name = name;
      this.value = value;
    }
  }

  /** Concrete subclass for testing the abstract StateManager. */
  static class TestStateManager extends StateManager<TestState> {
    TestStateManager(Path checkpointDir) {
      super(checkpointDir, SUBDIRECTORY, TestState.class);
    }
  }
}
