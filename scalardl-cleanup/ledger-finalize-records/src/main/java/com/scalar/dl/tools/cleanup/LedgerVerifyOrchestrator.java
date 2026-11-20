package com.scalar.dl.tools.cleanup;

import com.google.common.annotations.VisibleForTesting;
import com.scalar.db.api.DistributedStorageAdmin;
import com.scalar.db.config.DatabaseConfig;
import com.scalar.db.service.StorageFactory;
import com.scalar.dl.tools.common.LedgerConfigValidator;
import com.scalar.dl.tools.common.ScalarDlCleanupException;
import com.scalar.dl.tools.common.StorageValidator;
import com.scalar.dl.tools.common.VerificationResult;
import com.scalar.dl.tools.scan.ResumableScanner;
import com.scalar.dl.tools.scan.ResumableScannerFactory;
import com.scalar.dl.tools.scan.ScanResult;
import java.util.List;
import java.util.Properties;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrates the verification of {@code ledger-finalize-records}.
 *
 * <p>This class discovers all transactional tables, scans each table from the start, and reports
 * whether any record is still in a non-terminal state (PREPARED or DELETED), regardless of when the
 * record was prepared. It stops at the first such record.
 *
 * <p>The workflow is read-only and keeps no state: it changes no record, takes no checkpoint, and
 * emits no completion token. Every run discovers the tables again and scans all of them, so every
 * run also validates the namespace mapping of a multi-storage configuration, which {@code
 * ledger-finalize-records} does only when it starts a new run.
 */
public final class LedgerVerifyOrchestrator implements AutoCloseable {

  private static final Logger logger = LoggerFactory.getLogger(LedgerVerifyOrchestrator.class);

  private final DistributedStorageAdmin admin;
  private final ResumableScannerFactory scannerFactory;
  private final TransactionalTableDiscovery tableDiscovery;

  /** The validator of the namespace mapping. Null unless the storage is multi-storage. */
  @Nullable private final NamespaceMappingValidator namespaceMappingValidator;

  @VisibleForTesting
  LedgerVerifyOrchestrator(
      DistributedStorageAdmin admin,
      ResumableScannerFactory scannerFactory,
      String coordinatorNamespace,
      @Nullable NamespaceMappingValidator namespaceMappingValidator) {
    this.admin = admin;
    this.scannerFactory = scannerFactory;
    this.tableDiscovery = new TransactionalTableDiscovery(admin, coordinatorNamespace);
    this.namespaceMappingValidator = namespaceMappingValidator;
  }

  /**
   * Creates an orchestrator.
   *
   * @param props the properties used by the ScalarDL Ledger
   * @return a new orchestrator instance
   * @throws ScalarDlCleanupException if the configuration is not supported
   */
  public static LedgerVerifyOrchestrator create(Properties props) {
    DistributedStorageAdmin admin = null;
    try {
      DatabaseConfig databaseConfig = new DatabaseConfig(props);
      StorageValidator.validateAllowingMultiStorage(databaseConfig);
      LedgerConfigValidator.validate(databaseConfig);
      admin = StorageFactory.create(props).getStorageAdmin();
      return new LedgerVerifyOrchestrator(
          admin,
          new ResumableScannerFactory(databaseConfig),
          TransactionalTableDiscovery.resolveCoordinatorNamespace(databaseConfig),
          LedgerFinalizeOrchestrator.createNamespaceMappingValidator(admin, databaseConfig));
    } catch (Exception e) {
      if (admin != null) {
        admin.close();
      }
      throw e;
    }
  }

  /**
   * Executes the verification: validate the namespace mapping, discover the tables, and scan each
   * table until a non-terminal record is found.
   *
   * @return {@link VerificationResult#FAILED} if a non-terminal record was found, or {@link
   *     VerificationResult#PASSED} if all the tables were scanned to the end without finding one
   * @throws ScalarDlCleanupException if the namespace mapping does not match the storages, or if
   *     too few tables were found
   * @throws Exception if the discovery or the scan of a table fails
   */
  public VerificationResult execute() throws Exception {
    // A wrong namespace mapping would silently drop tables from the scan, so it must be caught
    // before a run can pass.
    if (namespaceMappingValidator != null) {
      namespaceMappingValidator.validate();
    }
    List<String> tableNames = tableDiscovery.discover();
    logger.info(
        "Starting the verification. {} target tables were found: {}",
        tableNames.size(),
        tableNames);

    for (String qualifiedTable : tableNames) {
      if (hasNonTerminalRecord(qualifiedTable)) {
        return VerificationResult.FAILED;
      }
    }
    return VerificationResult.PASSED;
  }

  /**
   * Scans a single table from the start and returns whether it has a non-terminal record. The scan
   * stops at the first one.
   */
  private boolean hasNonTerminalRecord(String qualifiedTable) throws Exception {
    String[] parts = qualifiedTable.split("\\.", 2);
    String namespace = parts[0];
    String tableName = parts[1];

    logger.info("Starting to scan the table: {}", qualifiedTable);

    VerifyTransactionRecordHandler handler =
        new VerifyTransactionRecordHandler(namespace, tableName);

    try (ResumableScanner scanner = scannerFactory.create(namespace)) {
      ScanResult scanResult = scanner.scan(namespace, tableName, handler);
      logger.info(
          "Finished scanning the table: {}. {} records were scanned.",
          qualifiedTable,
          scanResult.getTotalScanned());
    } catch (Exception e) {
      // The handler stops the scan by throwing, but the scan can also exit with the storage error
      // of another worker. The detection decides the result either way, so that such an error
      // cannot hide it.
      if (!handler.isDetected()) {
        throw e;
      }
    }

    if (handler.isDetected()) {
      logger.info(
          "Stopped scanning the table: {}. A record in a non-terminal state was found.",
          qualifiedTable);
      return true;
    }
    return false;
  }

  /**
   * Releases the resources held by this orchestrator. Any failure during close is logged and
   * suppressed rather than propagated.
   */
  @Override
  public void close() {
    try {
      admin.close();
    } catch (Exception e) {
      logger.warn("Failed to close DistributedStorageAdmin.", e);
    }
  }
}
