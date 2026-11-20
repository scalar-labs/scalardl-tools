package com.scalar.dl.tools.cleanup;

import com.google.common.annotations.VisibleForTesting;
import com.scalar.db.api.DistributedStorage;
import com.scalar.db.api.DistributedStorageAdmin;
import com.scalar.db.config.DatabaseConfig;
import com.scalar.db.service.StorageFactory;
import com.scalar.dl.tools.common.AuditorInternalValues;
import com.scalar.dl.tools.common.ScalarDlCleanupException;
import com.scalar.dl.tools.common.StorageValidator;
import com.scalar.dl.tools.common.VerificationResult;
import com.scalar.dl.tools.scan.ResumableScanner;
import com.scalar.dl.tools.scan.ResumableScannerFactory;
import com.scalar.dl.tools.scan.ScanResult;
import java.util.List;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrates the verification that {@code auditor-finalize-records} left no write lock behind.
 *
 * <p>This class discovers all {@code asset_lock} tables and scans each of them from the start,
 * looking for a record whose lock type is {@code WRITE}. It stops at the first one.
 *
 * <p>The workflow is read-only and keeps no state: it changes no record, does not connect to the
 * Auditor server, and takes no checkpoint, so every run discovers the namespaces again and scans
 * all of them from the start.
 */
public final class AuditorVerifyOrchestrator implements AutoCloseable {

  private static final Logger logger = LoggerFactory.getLogger(AuditorVerifyOrchestrator.class);

  private final DistributedStorageAdmin admin;
  private final DistributedStorage storage;
  private final ResumableScannerFactory scannerFactory;
  private final String baseNamespace;
  private final AuditorNamespaceDiscovery namespaceDiscovery;

  @VisibleForTesting
  AuditorVerifyOrchestrator(
      DistributedStorageAdmin admin,
      DistributedStorage storage,
      ResumableScannerFactory scannerFactory,
      String baseNamespace) {
    this.admin = admin;
    this.storage = storage;
    this.scannerFactory = scannerFactory;
    this.baseNamespace = baseNamespace;
    this.namespaceDiscovery = new AuditorNamespaceDiscovery(admin, storage, baseNamespace);
  }

  /**
   * Creates an orchestrator.
   *
   * <p>Besides the storage settings, only the Auditor base namespace ({@code
   * scalar.dl.auditor.namespace}) is read. The settings for connecting to the Auditor server are
   * not read, so they may be absent, and a certificate file they point to need not exist.
   *
   * @param props the properties used by the ScalarDL Auditor
   * @return a new orchestrator instance
   * @throws ScalarDlCleanupException if the configuration is not supported
   */
  public static AuditorVerifyOrchestrator create(Properties props) {
    DistributedStorageAdmin admin = null;
    DistributedStorage storage = null;
    try {
      DatabaseConfig databaseConfig = new DatabaseConfig(props);
      StorageValidator.validate(databaseConfig);
      StorageFactory storageFactory = StorageFactory.create(props);
      admin = storageFactory.getStorageAdmin();
      storage = storageFactory.getStorage();

      String baseNamespace =
          props.getProperty(
              AuditorInternalValues.AUDITOR_NAMESPACE_PROPERTY,
              AuditorInternalValues.DEFAULT_BASE_NAMESPACE);

      ResumableScannerFactory scannerFactory = new ResumableScannerFactory(databaseConfig);
      return new AuditorVerifyOrchestrator(admin, storage, scannerFactory, baseNamespace);
    } catch (Exception e) {
      if (storage != null) {
        storage.close();
      }
      if (admin != null) {
        admin.close();
      }
      throw e;
    }
  }

  /**
   * Executes the verification: discover the logical namespaces and scan each one's {@code
   * asset_lock} table from the start, in order, until a write lock is found.
   *
   * @return {@link VerificationResult#FAILED} if a write lock was found, in which case the
   *     remaining namespaces are not scanned; {@link VerificationResult#PASSED} if every table was
   *     scanned to the end and none was found
   * @throws Exception if the discovery or a scan fails without a write lock having been found
   */
  public VerificationResult execute() throws Exception {
    List<String> namespaces = namespaceDiscovery.discoverLogicalNamespaces();
    logger.info(
        "asset_lock tables in {} namespaces will be verified: {}", namespaces.size(), namespaces);

    for (String logicalNamespace : namespaces) {
      if (hasWriteLock(logicalNamespace)) {
        return VerificationResult.FAILED;
      }
    }
    return VerificationResult.PASSED;
  }

  /**
   * Scans a single namespace's {@code asset_lock} table from the start and returns whether a write
   * lock was found.
   *
   * <p>The handler stops the scan by throwing, so a scan that found a write lock ends with an
   * exception. The verdict is taken from the handler, not from the exception: the scan may end with
   * a database error of another scan worker instead, which must not hide the write lock.
   */
  private boolean hasWriteLock(String logicalNamespace) throws Exception {
    String physicalNamespace =
        AuditorInternalValues.resolveNamespace(baseNamespace, logicalNamespace);

    logger.info("Verifying asset_lock table in namespace: {}", physicalNamespace);

    VerifyAssetLockHandler handler = new VerifyAssetLockHandler(physicalNamespace);
    try (ResumableScanner scanner = scannerFactory.create(physicalNamespace)) {
      ScanResult scanResult =
          scanner.scan(physicalNamespace, AuditorInternalValues.ASSET_LOCK_TABLE_NAME, handler);

      logger.info(
          "Finished asset_lock table in namespace {}: {} records were scanned, no write lock was"
              + " found.",
          physicalNamespace,
          scanResult.getTotalScanned());
    } catch (Exception e) {
      if (!handler.isDetected()) {
        throw e;
      }
      logger.info(
          "Stopped verifying asset_lock table in namespace {}: a write lock was found.",
          physicalNamespace);
    }
    return handler.isDetected();
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
    try {
      storage.close();
    } catch (Exception e) {
      logger.warn("Failed to close DistributedStorage.", e);
    }
  }
}
