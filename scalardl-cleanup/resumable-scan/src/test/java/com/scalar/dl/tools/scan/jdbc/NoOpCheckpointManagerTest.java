package com.scalar.dl.tools.scan.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

class NoOpCheckpointManagerTest {

  private static final String TABLE = "no_op_checkpoint_ns.table";

  private final NoOpCheckpointManager manager = new NoOpCheckpointManager();

  @Test
  void load_afterPersist_shouldReturnNull() {
    // Arrange
    manager.initCheckpointFor(TABLE);

    // Act
    manager.persistLastKey(TABLE, "[1]");

    // Assert
    assertThat(manager.loadLastKey(TABLE)).isNull();
  }

  @Test
  void initPersistAndClear_shouldCreateNoFile() {
    // Act
    manager.initCheckpointFor(TABLE);
    manager.persistLastKey(TABLE, "[1]");
    manager.clearCheckpointFor(TABLE);

    // Assert
    assertThat(Paths.get(TABLE)).doesNotExist();
  }
}
