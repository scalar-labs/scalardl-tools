package com.scalar.dl.tools.cleanup;

import com.google.common.hash.Hashing;
import com.scalar.db.config.DatabaseConfig;
import java.nio.charset.StandardCharsets;

/**
 * Computes the IDs that identify storages in the checkpoint state.
 *
 * <p>An ID is a hash of the storage type, the contact points, the contact port and the username,
 * which together identify the database that the storage connects to. The username is included
 * because some databases choose the database to connect to by the username when the contact points
 * do not name one. These values are hashed because the ID is used as a directory name, and the
 * contact points can have characters that cannot be used in it. The same database written in
 * different ways or used by different users gets different IDs, which only makes its tables scanned
 * twice.
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
            + databaseConfig.getContactPort()
            + "\n"
            + databaseConfig.getUsername().orElse("");
    return Hashing.sha256()
        .hashString(connection, StandardCharsets.UTF_8)
        .toString()
        .substring(0, ID_LENGTH);
  }
}
