package com.scalar.dl.tools.common;

import com.scalar.db.config.DatabaseConfig;
import com.scalar.db.storage.cosmos.CosmosConfig;
import com.scalar.db.storage.jdbc.JdbcConfig;
import com.scalar.db.storage.multistorage.MultiStorageConfig;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/** Validates that the configured ScalarDB storage is supported by the tool. */
public final class StorageValidator {

  private StorageValidator() {}

  /**
   * Throws if the configured storage is not Cosmos DB.
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

  /**
   * Throws if the configured storage is neither Cosmos DB nor a supported multi-storage
   * configuration.
   *
   * <p>A multi-storage configuration is supported if every storage in it is Cosmos DB or a JDBC
   * database and the deprecated table mapping is not used. The tools resolve the storage of a table
   * by its namespace only, so a table that the table mapping routes to another storage than its
   * namespace's would be missed.
   *
   * @param databaseConfig the ScalarDB database configuration
   * @throws ScalarDlCleanupException if the configured storage is not supported, or if the
   *     multi-storage configuration is invalid
   */
  public static void validateAllowingMultiStorage(DatabaseConfig databaseConfig) {
    Objects.requireNonNull(databaseConfig, "databaseConfig must not be null");
    if (!MultiStorageConfig.STORAGE_NAME.equals(databaseConfig.getStorage())) {
      validate(databaseConfig);
      return;
    }

    MultiStorageConfig multiStorageConfig = parseMultiStorageConfig(databaseConfig);
    for (Map.Entry<String, Properties> entry :
        multiStorageConfig.getDatabasePropertiesMap().entrySet()) {
      String storage = new DatabaseConfig(entry.getValue()).getStorage();
      if (!CosmosConfig.STORAGE_NAME.equals(storage) && !JdbcConfig.STORAGE_NAME.equals(storage)) {
        throw new ScalarDlCleanupException(
            ScalarDlCleanupError.UNSUPPORTED_STORAGE_IN_MULTI_STORAGE, entry.getKey(), storage);
      }
    }

    @SuppressWarnings("deprecation")
    Map<String, String> tableStorageMap = multiStorageConfig.getTableStorageMap();
    if (!tableStorageMap.isEmpty()) {
      throw new ScalarDlCleanupException(
          ScalarDlCleanupError.MULTI_STORAGE_TABLE_MAPPING_NOT_SUPPORTED);
    }
  }

  /**
   * Parses the multi-storage configuration. {@link MultiStorageConfig} throws various runtime
   * exceptions on a malformed configuration, such as {@link IllegalArgumentException} for an
   * unknown storage name and {@link ArrayIndexOutOfBoundsException} for a mapping entry without a
   * colon. They are all configuration errors, so they are reported as a user error.
   */
  private static MultiStorageConfig parseMultiStorageConfig(DatabaseConfig databaseConfig) {
    try {
      return new MultiStorageConfig(databaseConfig);
    } catch (RuntimeException e) {
      throw new ScalarDlCleanupException(
          ScalarDlCleanupError.INVALID_MULTI_STORAGE_CONFIGURATION,
          e,
          e.getMessage() != null ? e.getMessage() : e.toString());
    }
  }
}
