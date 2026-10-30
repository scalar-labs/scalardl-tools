package com.scalar.dl.tools.scan;

import com.scalar.db.config.DatabaseConfig;
import com.scalar.db.storage.cosmos.CosmosConfig;
import com.scalar.db.storage.jdbc.JdbcConfig;
import com.scalar.db.storage.multistorage.MultiStorageConfig;
import com.scalar.dl.tools.scan.cosmos.CosmosResumableScanner;
import com.scalar.dl.tools.scan.jdbc.JdbcResumableScanner;
import java.nio.file.Path;
import javax.annotation.Nullable;

/**
 * Factory for creating {@link ResumableScanner} instances based on the configured storage.
 *
 * <p>With multi-storage, the factory resolves the storage that the namespace to scan is mapped to,
 * and creates the scanner for that storage. Each scanner works on a single storage.
 */
public final class ResumableScannerFactory {

  private final DatabaseConfig databaseConfig;
  @Nullable private final MultiStorageConfig multiStorageConfig;

  public ResumableScannerFactory(DatabaseConfig databaseConfig) {
    this.databaseConfig = databaseConfig;
    this.multiStorageConfig =
        MultiStorageConfig.STORAGE_NAME.equals(databaseConfig.getStorage())
            ? new MultiStorageConfig(databaseConfig)
            : null;
  }

  /**
   * Creates a new {@link ResumableScanner} for the tables in the given namespace.
   *
   * @param namespace ScalarDB namespace of the tables to scan
   * @param checkpointDir directory for scan checkpoint state
   * @return a new scanner for the storage of the namespace
   * @throws IllegalArgumentException if the storage of the namespace is not supported
   */
  public ResumableScanner create(String namespace, Path checkpointDir) {
    DatabaseConfig config = resolveDatabaseConfig(namespace);
    String storage = config.getStorage();
    if (CosmosConfig.STORAGE_NAME.equals(storage)) {
      return new CosmosResumableScanner(config, checkpointDir);
    }
    if (JdbcConfig.STORAGE_NAME.equals(storage)) {
      return new JdbcResumableScanner(config, checkpointDir);
    }
    throw new IllegalArgumentException(
        "This tool only supports Cosmos DB and JDBC databases. Configured storage: " + storage);
  }

  /**
   * Returns the configuration of the storage of the given namespace. With multi-storage, the
   * storage is the one that the namespace is mapped to, or the default storage if the namespace is
   * not mapped. The deprecated table mapping is not considered.
   *
   * @param namespace ScalarDB namespace
   * @return the configuration of the storage of the namespace
   */
  public DatabaseConfig resolveDatabaseConfig(String namespace) {
    if (multiStorageConfig == null) {
      return databaseConfig;
    }
    String storageName =
        multiStorageConfig
            .getNamespaceStorageMap()
            .getOrDefault(namespace, multiStorageConfig.getDefaultStorage());
    return new DatabaseConfig(multiStorageConfig.getDatabasePropertiesMap().get(storageName));
  }
}
