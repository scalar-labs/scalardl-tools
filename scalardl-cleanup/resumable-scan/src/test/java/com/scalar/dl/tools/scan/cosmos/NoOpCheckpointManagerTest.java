package com.scalar.dl.tools.scan.cosmos;

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
    manager.persistFeedRanges(TABLE, "[]");
    manager.persistContinuationToken(TABLE, "range", "token");

    // Assert
    assertThat(manager.loadFeedRanges(TABLE)).isNull();
    assertThat(manager.loadContinuationToken(TABLE, "range")).isNull();
  }

  @Test
  void initPersistAndClear_shouldCreateNoFile() {
    // Act
    manager.initCheckpointFor(TABLE);
    manager.persistFeedRanges(TABLE, "[]");
    manager.persistContinuationToken(TABLE, "range", "token");
    manager.clearCheckpointFor(TABLE);

    // Assert
    assertThat(Paths.get(TABLE)).doesNotExist();
  }
}
