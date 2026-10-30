package com.scalar.dl.tools.cleanup;

import com.google.common.hash.Hashing;
import com.scalar.db.config.DatabaseConfig;
import java.nio.charset.StandardCharsets;

/**
 * Computes the IDs that identify storages in the checkpoint state.
 *
 * <p>An ID is a hash of the storage type, the contact points and the contact port, which together
 * identify the database that the storage connects to. They are hashed because the ID is used as a
 * directory name, and the contact points can have characters that cannot be used in it. The same
 * database written in different ways gets different IDs, which only makes its tables scanned twice.
 */
final class StorageIds {

  private static final int ID_LENGTH = 16;

  private StorageIds() {}

  /**
   * Returns the ID of the storage of the given configuration.
   *
   * @param databaseConfig the configuration of a single storage, not multi-storage
   * @return the storage ID
   */
  static String of(DatabaseConfig databaseConfig) {
    String connection =
        databaseConfig.getStorage()
            + "\n"
            + String.join(",", databaseConfig.getContactPoints())
            + "\n"
            + databaseConfig.getContactPort();
    return Hashing.sha256()
        .hashString(connection, StandardCharsets.UTF_8)
        .toString()
        .substring(0, ID_LENGTH);
  }
}
