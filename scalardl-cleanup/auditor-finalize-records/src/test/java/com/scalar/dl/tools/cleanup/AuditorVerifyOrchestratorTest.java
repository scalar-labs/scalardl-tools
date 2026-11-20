package com.scalar.dl.tools.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.scalar.db.api.DistributedStorage;
import com.scalar.db.api.DistributedStorageAdmin;
import com.scalar.db.api.Result;
import com.scalar.db.api.Scan;
import com.scalar.db.api.Scanner;
import com.scalar.db.config.DatabaseConfig;
import com.scalar.db.service.StorageFactory;
import com.scalar.dl.client.config.ClientConfig;
import com.scalar.dl.tools.common.AuditorInternalValues;
import com.scalar.dl.tools.common.ScalarDlCleanupError;
import com.scalar.dl.tools.common.ScalarDlCleanupException;
import com.scalar.dl.tools.common.VerificationResult;
import com.scalar.dl.tools.scan.RecordHandler;
import com.scalar.dl.tools.scan.ResumableScanner;
import com.scalar.dl.tools.scan.ResumableScannerFactory;
import com.scalar.dl.tools.scan.ScanResult;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

@SuppressWarnings("resource")
class AuditorVerifyOrchestratorTest {

  private static final String NAMESPACE = "auditor";
  private static final String NS1_PHYSICAL = "auditor_ns1";
  private static final String NS2_PHYSICAL = "auditor_ns2";
  private static final String ASSET_LOCK = "asset_lock";
  private static final long OLD_TIMESTAMP = 1L;

  private DistributedStorageAdmin admin;
  private DistributedStorage storage;
  private ResumableScanner scanner;
  private ResumableScannerFactory scannerFactory;

  @BeforeEach
  void setUp() {
    admin = mock(DistributedStorageAdmin.class);
    storage = mock(DistributedStorage.class);
    scanner = mock(ResumableScanner.class);
    scannerFactory = mock(ResumableScannerFactory.class);
    when(scannerFactory.create(anyString())).thenReturn(scanner);
  }

  private AuditorVerifyOrchestrator orchestrator() {
    return new AuditorVerifyOrchestrator(admin, storage, scannerFactory, NAMESPACE);
  }

  /** Registers the context namespaces {@code ns1} and {@code ns2} besides the default one. */
  private void registerContextNamespaces() throws Exception {
    when(admin.tableExists(NAMESPACE, AuditorInternalValues.NAMESPACE_TABLE_NAME)).thenReturn(true);
    List<Result> registered = Arrays.asList(namespaceRecord("ns1"), namespaceRecord("ns2"));
    when(storage.scan(any(Scan.class)))
        .thenAnswer(
            invocation -> {
              Scanner namespaceScanner = mock(Scanner.class);
              when(namespaceScanner.iterator()).thenReturn(registered.iterator());
              return namespaceScanner;
            });
  }

  private static Result namespaceRecord(String name) {
    Result result = mock(Result.class);
    when(result.getText(AuditorInternalValues.NAMESPACE_TABLE_NAME_COLUMN_NAME)).thenReturn(name);
    return result;
  }

  private static Result lockRecord(String assetId, int lockType, long lastUpdatedAt) {
    Result record = mock(Result.class);
    when(record.getText(AuditorInternalValues.ASSET_LOCK_TABLE_ID_COLUMN_NAME)).thenReturn(assetId);
    when(record.getInt(AuditorInternalValues.ASSET_LOCK_TABLE_LOCK_TYPE_COLUMN_NAME))
        .thenReturn(lockType);
    when(record.getBigInt(AuditorInternalValues.ASSET_LOCK_TABLE_LAST_UPDATED_AT_COLUMN_NAME))
        .thenReturn(lastUpdatedAt);
    return record;
  }

  private static Result released(String assetId) {
    return lockRecord(assetId, AuditorInternalValues.LOCK_TYPE_NONE, OLD_TIMESTAMP);
  }

  private static Result readLock(String assetId) {
    return lockRecord(assetId, AuditorInternalValues.LOCK_TYPE_READ, OLD_TIMESTAMP);
  }

  private static Result writeLock(String assetId) {
    return lockRecord(assetId, AuditorInternalValues.LOCK_TYPE_WRITE, OLD_TIMESTAMP);
  }

  /**
   * Makes the scan of the given physical namespace deliver the given records to the handler, the
   * way a scanner does: a failure of the handler aborts the scan and propagates as-is.
   */
  private void stubAssetLockTable(String physicalNamespace, Result... records) throws Exception {
    when(scanner.scan(eq(physicalNamespace), eq(ASSET_LOCK), any()))
        .thenAnswer(
            invocation -> {
              RecordHandler handler = invocation.getArgument(2);
              for (Result record : records) {
                handler.handle(record);
              }
              return new ScanResult(records.length);
            });
  }

  private static Properties cosmosProperties() {
    Properties props = new Properties();
    props.setProperty(DatabaseConfig.STORAGE, "cosmos");
    return props;
  }

  /**
   * Runs {@code create} with the given properties against a storage whose namespace registry lookup
   * fails, executes the orchestrator, and returns the admin the orchestrator was given. The
   * namespace the lookup was made in is the base namespace that {@code create} read.
   */
  private static DistributedStorageAdmin createAndExecuteUntilNamespaceLookup(Properties props)
      throws Exception {
    DistributedStorageAdmin createdAdmin = mock(DistributedStorageAdmin.class);
    when(createdAdmin.tableExists(anyString(), anyString()))
        .thenThrow(new IllegalStateException("lookup stopped"));
    StorageFactory storageFactory = mock(StorageFactory.class);
    when(storageFactory.getStorageAdmin()).thenReturn(createdAdmin);
    when(storageFactory.getStorage()).thenReturn(mock(DistributedStorage.class));

    try (MockedStatic<StorageFactory> storageFactoryStatic = mockStatic(StorageFactory.class)) {
      storageFactoryStatic
          .when(() -> StorageFactory.create(any(Properties.class)))
          .thenReturn(storageFactory);

      AuditorVerifyOrchestrator orchestrator = AuditorVerifyOrchestrator.create(props);
      assertThatThrownBy(orchestrator::execute)
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("lookup stopped");
    }
    return createdAdmin;
  }

  @Test
  void create_nonCosmosStorageGiven_shouldThrowUnsupportedStorage() {
    // Arrange
    Properties props = new Properties();
    props.setProperty(DatabaseConfig.STORAGE, "jdbc");

    // Act & Assert
    assertThatThrownBy(() -> AuditorVerifyOrchestrator.create(props))
        .isInstanceOf(ScalarDlCleanupException.class)
        .hasMessage(ScalarDlCleanupError.UNSUPPORTED_STORAGE.buildMessage("jdbc"));
  }

  @Test
  void create_noAuditorClientPropertiesGiven_shouldSucceedWithDefaultBaseNamespace()
      throws Exception {
    // Arrange — only the storage settings; nothing under scalar.dl.client.auditor.*.
    Properties props = cosmosProperties();

    // Act
    DistributedStorageAdmin createdAdmin = createAndExecuteUntilNamespaceLookup(props);

    // Assert
    verify(createdAdmin).tableExists("auditor", AuditorInternalValues.NAMESPACE_TABLE_NAME);
  }

  @Test
  void create_nonexistentCaRootCertPathGiven_shouldSucceed(@TempDir Path tempDir) throws Exception {
    // Arrange — the properties of an Auditor with TLS, whose certificate file is not mounted.
    Properties props = cosmosProperties();
    props.setProperty(ClientConfig.AUDITOR_TLS_ENABLED, "true");
    props.setProperty(
        ClientConfig.AUDITOR_TLS_CA_ROOT_CERT_PATH, tempDir.resolve("missing-ca.pem").toString());

    // Act
    DistributedStorageAdmin createdAdmin = createAndExecuteUntilNamespaceLookup(props);

    // Assert
    verify(createdAdmin).tableExists("auditor", AuditorInternalValues.NAMESPACE_TABLE_NAME);
  }

  @Test
  void create_auditorNamespaceGiven_shouldUseItAsBaseNamespace() throws Exception {
    // Arrange
    Properties props = cosmosProperties();
    props.setProperty(AuditorInternalValues.AUDITOR_NAMESPACE_PROPERTY, "custom_auditor");

    // Act
    DistributedStorageAdmin createdAdmin = createAndExecuteUntilNamespaceLookup(props);

    // Assert
    verify(createdAdmin).tableExists("custom_auditor", AuditorInternalValues.NAMESPACE_TABLE_NAME);
  }

  @Test
  void execute_onlyReleasedLocksInAllNamespacesGiven_shouldReturnPassed() throws Exception {
    // Arrange
    registerContextNamespaces();
    stubAssetLockTable(NAMESPACE, released("a1"), released("a2"));
    stubAssetLockTable(NS1_PHYSICAL, released("b1"));
    stubAssetLockTable(NS2_PHYSICAL, released("c1"));

    // Act
    VerificationResult result = orchestrator().execute();

    // Assert — every namespace's asset_lock table was scanned, each with its own scanner.
    assertThat(result).isEqualTo(VerificationResult.PASSED);
    verify(scanner).scan(eq(NAMESPACE), eq(ASSET_LOCK), any());
    verify(scanner).scan(eq(NS1_PHYSICAL), eq(ASSET_LOCK), any());
    verify(scanner).scan(eq(NS2_PHYSICAL), eq(ASSET_LOCK), any());
    verify(scannerFactory).create(NAMESPACE);
    verify(scannerFactory).create(NS1_PHYSICAL);
    verify(scannerFactory).create(NS2_PHYSICAL);
    verify(scanner, times(3)).close();
  }

  @Test
  void execute_onlyReadLocksRemainingGiven_shouldReturnPassed() throws Exception {
    // Arrange
    registerContextNamespaces();
    stubAssetLockTable(NAMESPACE, released("a1"), readLock("a2"));
    stubAssetLockTable(NS1_PHYSICAL, readLock("b1"));
    stubAssetLockTable(NS2_PHYSICAL, readLock("c1"), released("c2"));

    // Act
    VerificationResult result = orchestrator().execute();

    // Assert
    assertThat(result).isEqualTo(VerificationResult.PASSED);
  }

  @Test
  void execute_writeLockInDefaultNamespaceGiven_shouldReturnFailedWithoutScanningTheRest()
      throws Exception {
    // Arrange
    registerContextNamespaces();
    stubAssetLockTable(NAMESPACE, released("a1"), writeLock("a2"), released("a3"));
    stubAssetLockTable(NS1_PHYSICAL, released("b1"));
    stubAssetLockTable(NS2_PHYSICAL, released("c1"));

    // Act
    VerificationResult result = orchestrator().execute();

    // Assert
    assertThat(result).isEqualTo(VerificationResult.FAILED);
    verify(scanner, never()).scan(eq(NS1_PHYSICAL), eq(ASSET_LOCK), any());
    verify(scanner, never()).scan(eq(NS2_PHYSICAL), eq(ASSET_LOCK), any());
    verify(scanner).close();
  }

  @Test
  void execute_writeLockOnlyInContextNamespaceGiven_shouldReturnFailed() throws Exception {
    // Arrange — the last namespace in the scan order is the only one with a write lock.
    registerContextNamespaces();
    stubAssetLockTable(NAMESPACE, released("a1"));
    stubAssetLockTable(NS1_PHYSICAL, readLock("b1"));
    stubAssetLockTable(NS2_PHYSICAL, released("c1"), writeLock("c2"));

    // Act
    VerificationResult result = orchestrator().execute();

    // Assert
    assertThat(result).isEqualTo(VerificationResult.FAILED);
  }

  @Test
  void execute_writeLockUpdatedWithinLockValidPeriodGiven_shouldReturnFailed() throws Exception {
    // Arrange — a write lock taken just now, well within the last 15 seconds.
    when(admin.tableExists(NAMESPACE, AuditorInternalValues.NAMESPACE_TABLE_NAME))
        .thenReturn(false);
    stubAssetLockTable(
        NAMESPACE,
        lockRecord("a1", AuditorInternalValues.LOCK_TYPE_WRITE, System.currentTimeMillis()));

    // Act
    VerificationResult result = orchestrator().execute();

    // Assert
    assertThat(result).isEqualTo(VerificationResult.FAILED);
  }

  @Test
  void execute_unknownLockTypeGiven_shouldThrowIllegalStateException() throws Exception {
    // Arrange
    when(admin.tableExists(NAMESPACE, AuditorInternalValues.NAMESPACE_TABLE_NAME))
        .thenReturn(false);
    stubAssetLockTable(NAMESPACE, released("a1"), lockRecord("a2", 99, OLD_TIMESTAMP));

    // Act & Assert
    assertThatThrownBy(() -> orchestrator().execute())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Unexpected lock_type value in asset_lock record: 99");
    verify(scanner).close();
  }

  @Test
  void execute_calledOnTwoOrchestratorsGiven_shouldScanAllNamespacesInBothRuns() throws Exception {
    // Arrange
    registerContextNamespaces();
    stubAssetLockTable(NAMESPACE, released("a1"));
    stubAssetLockTable(NS1_PHYSICAL, released("b1"));
    stubAssetLockTable(NS2_PHYSICAL, released("c1"));

    // Act
    VerificationResult first = orchestrator().execute();
    VerificationResult second = orchestrator().execute();

    // Assert — the second run starts over: no namespace is skipped as already done.
    assertThat(first).isEqualTo(VerificationResult.PASSED);
    assertThat(second).isEqualTo(VerificationResult.PASSED);
    verify(scanner, times(2)).scan(eq(NAMESPACE), eq(ASSET_LOCK), any());
    verify(scanner, times(2)).scan(eq(NS1_PHYSICAL), eq(ASSET_LOCK), any());
    verify(scanner, times(2)).scan(eq(NS2_PHYSICAL), eq(ASSET_LOCK), any());
  }

  @Test
  void execute_assetLockTableMissingInBaseNamespaceGiven_shouldPropagateScannerFailure()
      throws Exception {
    // Arrange — the scanner's own failure for a table without metadata.
    when(admin.tableExists(NAMESPACE, AuditorInternalValues.NAMESPACE_TABLE_NAME))
        .thenReturn(false);
    when(scanner.scan(eq(NAMESPACE), eq(ASSET_LOCK), any()))
        .thenThrow(new IllegalStateException("Table metadata not found for auditor.asset_lock"));

    // Act & Assert
    assertThatThrownBy(() -> orchestrator().execute())
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Table metadata not found for auditor.asset_lock");
    verify(scanner).close();
  }

  @Test
  void execute_scanFailureInLaterNamespaceGiven_shouldPropagateException() throws Exception {
    // Arrange
    registerContextNamespaces();
    stubAssetLockTable(NAMESPACE, released("a1"));
    when(scanner.scan(eq(NS1_PHYSICAL), eq(ASSET_LOCK), any()))
        .thenThrow(new RuntimeException("Cosmos DB unavailable"));

    // Act & Assert
    assertThatThrownBy(() -> orchestrator().execute())
        .isInstanceOf(RuntimeException.class)
        .hasMessage("Cosmos DB unavailable");
  }

  @Test
  void execute_databaseErrorAlongsideWriteLockGiven_shouldReturnFailed() throws Exception {
    // Arrange — one scan worker finds a write lock while the scan as a whole fails with the
    // database error of another worker.
    when(admin.tableExists(NAMESPACE, AuditorInternalValues.NAMESPACE_TABLE_NAME))
        .thenReturn(false);
    Result held = writeLock("a1");
    when(scanner.scan(eq(NAMESPACE), eq(ASSET_LOCK), any()))
        .thenAnswer(
            invocation -> {
              RecordHandler handler = invocation.getArgument(2);
              assertThatThrownBy(() -> handler.handle(held)).isInstanceOf(Exception.class);
              throw new RuntimeException("Cosmos DB unavailable");
            });

    // Act
    VerificationResult result = orchestrator().execute();

    // Assert
    assertThat(result).isEqualTo(VerificationResult.FAILED);
  }

  @Test
  void close_shouldCloseAdminAndStorage() {
    // Arrange
    AuditorVerifyOrchestrator orchestrator = orchestrator();

    // Act
    orchestrator.close();

    // Assert
    verify(admin).close();
    verify(storage).close();
  }
}
