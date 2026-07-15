package com.scalar.dl.tools.common;

import com.scalar.db.config.DatabaseConfig;
import com.scalar.db.storage.cosmos.CosmosConfig;
import java.util.Objects;

/** Validates that the configured ScalarDB storage is supported by the tool. */
public final class StorageValidator {

  private StorageValidator() {}

  /**
   * Throws if the configured storage is not supported. Only Cosmos DB is supported for now.
   *
   * @param databaseConfig the ScalarDB database configuration
   * @throws ScalarDlCleanupException if the configured storage is not supported
   */
  public static void validate(DatabaseConfig databaseConfig) {
    Objects.requireNonNull(databaseConfig, "databaseConfig must not be null");
    String storage = databaseConfig.getStorage();
    if (!CosmosConfig.STORAGE_NAME.equals(storage)) {
      throw new ScalarDlCleanupException(ScalarDlCleanupError.UNSUPPORTED_STORAGE, storage);
    }
  }
}
