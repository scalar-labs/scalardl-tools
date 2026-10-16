package com.scalar.dl.tools.scan.jdbc;

import com.scalar.dl.tools.scan.AbstractCheckpointManager;
import java.nio.file.Path;
import javax.annotation.Nullable;

/**
 * Manages scan-internal checkpoint state for {@link JdbcResumableScanner}: the primary key of the
 * last record of the last fully handled page, stored in {@code
 * <checkpointDir>/<tableName>/last_key.json}.
 */
class CheckpointManager extends AbstractCheckpointManager {

  private static final String LAST_KEY_FILE = "last_key.json";

  public CheckpointManager(Path checkpointDir) {
    super(checkpointDir);
  }

  /** Load the serialized last primary key for a given table, or null if none persisted. */
  @Nullable
  public String loadLastKey(String tableName) {
    return load(tableName, LAST_KEY_FILE);
  }

  /** Atomically persist the serialized last primary key for a given table. */
  public void persistLastKey(String tableName, String lastKeyJson) {
    persist(tableName, LAST_KEY_FILE, lastKeyJson);
  }
}
