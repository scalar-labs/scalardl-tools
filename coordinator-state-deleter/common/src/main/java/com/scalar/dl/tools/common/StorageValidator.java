package com.scalar.dl.tools.common;

import com.scalar.db.config.DatabaseConfig;
import com.scalar.db.storage.cosmos.CosmosConfig;

/** Validates that the configured ScalarDB storage is supported by the tool. */
public final class StorageValidator {

  private StorageValidator() {}

  /**
   * Throws if the configured storage is not supported. Only Cosmos DB is supported for now.
   *
   * @param databaseConfig the ScalarDB database configuration
   * @throws CoordinatorStateDeleterException if the configured storage is not supported
   */
  public static void validate(DatabaseConfig databaseConfig) {
    String storage = databaseConfig.getStorage();
    if (!CosmosConfig.STORAGE_NAME.equals(storage)) {
      throw new CoordinatorStateDeleterException(
          CoordinatorStateDeleterError.UNSUPPORTED_STORAGE, storage);
    }
  }
}
