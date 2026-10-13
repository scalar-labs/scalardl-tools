package com.scalar.dl.tools.scan;

import com.scalar.db.api.TableMetadata;
import com.scalar.db.config.DatabaseConfig;
import com.scalar.db.storage.cosmos.CosmosConfig;
import com.scalar.db.storage.jdbc.JdbcConfig;
import com.scalar.dl.tools.common.ScalarDlCleanupError;
import com.scalar.dl.tools.common.ScalarDlCleanupException;
import com.scalar.dl.tools.scan.cosmos.CosmosResumableScanner;
import com.scalar.dl.tools.scan.jdbc.JdbcResumableScanner;
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
   * @throws ScalarDlCleanupException if the configured storage is not supported
   */
  public ResumableScanner create(Path checkpointDir) {
    String storage = databaseConfig.getStorage();
    if (CosmosConfig.STORAGE_NAME.equals(storage)) {
      return new CosmosResumableScanner(databaseConfig, checkpointDir);
    }
    if (JdbcConfig.STORAGE_NAME.equals(storage)) {
      return new JdbcResumableScanner(databaseConfig, checkpointDir);
    }
    throw new ScalarDlCleanupException(ScalarDlCleanupError.UNSUPPORTED_STORAGE, storage);
  }

  /**
   * Throws if the scanner for the configured storage cannot resumably scan a table with the given
   * metadata. Callers that discover their target tables use this to fail before starting, rather
   * than partway through a multi-table run.
   *
   * @param qualifiedTableName the full table name, used in the error message
   * @param metadata the metadata of the table to scan
   * @throws ScalarDlCleanupException if the table cannot be scanned
   */
  public void validateScannable(String qualifiedTableName, TableMetadata metadata) {
    if (JdbcConfig.STORAGE_NAME.equals(databaseConfig.getStorage())) {
      JdbcResumableScanner.validateScannable(qualifiedTableName, metadata);
    }
  }
}
