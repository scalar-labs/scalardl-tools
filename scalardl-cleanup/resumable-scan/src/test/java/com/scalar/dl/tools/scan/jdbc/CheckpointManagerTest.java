package com.scalar.dl.tools.scan.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CheckpointManagerTest {

  @TempDir Path tempDir;
  private CheckpointManager manager;

  @BeforeEach
  void setUp() {
    manager = new CheckpointManager(tempDir.resolve("checkpoint"));
  }

  @Test
  void loadLastKey_nonExistentFileGiven_shouldReturnNull() {
    assertThat(manager.loadLastKey("ns.table")).isNull();
  }

  @Test
  void persistLastKey_shouldStoreLastKeyLoadableByLoadLastKey() {
    // Arrange
    manager.initCheckpointFor("ns.table");

    // Act
    manager.persistLastKey("ns.table", "[1]");
    manager.persistLastKey("ns.table", "[2]");

    // Assert
    assertThat(manager.loadLastKey("ns.table")).isEqualTo("[2]");
    assertThat(tempDir.resolve("checkpoint").resolve("ns.table").resolve("last_key.json")).exists();
  }
}
