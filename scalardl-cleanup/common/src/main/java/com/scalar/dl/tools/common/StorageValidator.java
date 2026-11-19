package com.scalar.dl.tools.common;

import com.scalar.db.config.ConfigUtils;
import com.scalar.db.config.DatabaseConfig;
import com.scalar.db.storage.cosmos.CosmosConfig;
import com.scalar.db.storage.jdbc.JdbcConfig;
import com.scalar.db.storage.multistorage.MultiStorageConfig;
import com.scalar.db.transaction.consensuscommit.ConsensusCommitConfig;
import com.scalar.db.transaction.consensuscommit.CoordinatorStateAccessor;
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
   * <p>A multi-storage configuration is supported if all of the following hold:
   *
   * <ul>
   *   <li>Every storage in it is Cosmos DB or a JDBC database.
   *   <li>The deprecated table mapping is not used. The tools resolve the storage of a table by its
   *       namespace only, so a table that the table mapping routes to another storage than its
   *       namespace's would be missed.
   *   <li>The default storage is Cosmos DB. ScalarDL's namespaces are expected to belong to the
   *       default storage without being mapped, so with another default storage they would be
   *       missed, including the ones that Ledger creates later.
   *   <li>The Coordinator namespace is not mapped to a storage other than the default storage. The
   *       Coordinator table must be in the Ledger's Cosmos DB account.
   *   <li>Every storage other than the default storage has a namespace mapped to it. The tools
   *       reach such a storage only through the namespace mapping, so without one its tables would
   *       be missed.
   * </ul>
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

    String defaultStorageName = multiStorageConfig.getDefaultStorage();
    String defaultStorage =
        new DatabaseConfig(multiStorageConfig.getDatabasePropertiesMap().get(defaultStorageName))
            .getStorage();
    if (!CosmosConfig.STORAGE_NAME.equals(defaultStorage)) {
      throw new ScalarDlCleanupException(
          ScalarDlCleanupError.MULTI_STORAGE_DEFAULT_STORAGE_NOT_COSMOS,
          defaultStorageName,
          defaultStorage);
    }

    // Read the property in the same way as ConsensusCommitConfig does, without creating it,
    // because it cannot be created with another transaction manager, which LedgerConfigValidator
    // reports as a user error.
    String coordinatorNamespace =
        ConfigUtils.getString(
            databaseConfig.getProperties(),
            ConsensusCommitConfig.COORDINATOR_NAMESPACE,
            CoordinatorStateAccessor.NAMESPACE);
    String coordinatorStorageName =
        multiStorageConfig.getNamespaceStorageMap().get(coordinatorNamespace);
    if (coordinatorStorageName != null && !coordinatorStorageName.equals(defaultStorageName)) {
      throw new ScalarDlCleanupException(
          ScalarDlCleanupError.MULTI_STORAGE_COORDINATOR_NAMESPACE_NOT_IN_DEFAULT_STORAGE,
          coordinatorNamespace,
          coordinatorStorageName);
    }

    for (String storageName : multiStorageConfig.getDatabasePropertiesMap().keySet()) {
      if (!storageName.equals(defaultStorageName)
          && !multiStorageConfig.getNamespaceStorageMap().containsValue(storageName)) {
        throw new ScalarDlCleanupException(
            ScalarDlCleanupError.MULTI_STORAGE_STORAGE_WITHOUT_MAPPED_NAMESPACE, storageName);
      }
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
