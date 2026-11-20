package com.scalar.dl.tools.scan.cosmos;

import javax.annotation.Nullable;

/**
 * A {@link CheckpointManager} that stores nothing: it never touches the file system, discards
 * everything it is asked to persist, and always reports that no checkpoint exists.
 *
 * <p>A {@link CosmosResumableScanner} with this checkpoint manager saves no progress and scans a
 * table from the beginning on every scan.
 */
final class NoOpCheckpointManager extends CheckpointManager {

  NoOpCheckpointManager() {
    // No checkpoint directory. Every operation that would use it is overridden below.
    super(null);
  }

  @Override
  public void initCheckpointFor(String tableName) {}

  @Override
  public void clearCheckpointFor(String tableName) {}

  @Override
  @Nullable
  protected String load(String tableName, String fileName) {
    return null;
  }

  @Override
  protected void persist(String tableName, String fileName, String content) {}
}
