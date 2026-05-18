package com.scalar.dl.tools.scan;

import com.scalar.db.config.DatabaseConfig;
import com.scalar.db.storage.cosmos.CosmosConfig;
import com.scalar.dl.tools.scan.cosmos.CosmosResumableScanner;
import java.nio.file.Path;

/** Factory for creating {@link ResumableScanner} instances based on the configured storage. */
public final class ResumableScannerFactory {

  private final DatabaseConfig databaseConfig;

  public ResumableScannerFactory(DatabaseConfig databaseConfig) {
    this.databaseConfig = databaseConfig;
  }

  /**
   * Creates a new {@link ResumableScanner} for the given checkpoint directory.
   *
   * @param checkpointDir directory for scan checkpoint state
   * @return a new scanner
   * @throws IllegalStateException if the configured storage is not supported
   */
  public ResumableScanner create(Path checkpointDir) {
    String storage = databaseConfig.getStorage();
    if (storage.equals(CosmosConfig.STORAGE_NAME)) {
      return new CosmosResumableScanner(databaseConfig, checkpointDir);
    }
    throw new IllegalStateException(
        "This tool only supports Cosmos DB. Configured storage: " + storage);
  }
}
