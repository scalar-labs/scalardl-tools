package com.scalar.dl.tools.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.scalar.db.api.DistributedStorageAdmin;
import com.scalar.db.api.Result;
import com.scalar.db.api.TableMetadata;
import com.scalar.db.api.TransactionState;
import com.scalar.db.config.DatabaseConfig;
import com.scalar.db.io.DataType;
import com.scalar.db.service.StorageFactory;
import com.scalar.db.service.TransactionFactory;
import com.scalar.db.storage.multistorage.MultiStorageConfig;
import com.scalar.db.transaction.consensuscommit.ConsensusCommitUtils;
import com.scalar.db.transaction.consensuscommit.CoordinatorStateAccessor;
import com.scalar.dl.tools.common.ScalarDlCleanupError;
import com.scalar.dl.tools.common.ScalarDlCleanupException;
import com.scalar.dl.tools.common.VerificationResult;
import com.scalar.dl.tools.scan.RecordHandler;
import com.scalar.dl.tools.scan.ResumableScanner;
import com.scalar.dl.tools.scan.ResumableScannerFactory;
import com.scalar.dl.tools.scan.ScanResult;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

@SuppressWarnings("resource")
class LedgerVerifyOrchestratorTest {

  private static final TableMetadata TRANSACTION_TABLE_METADATA =
      ConsensusCommitUtils.buildTransactionTableMetadata(
          TableMetadata.newBuilder()
              .addColumn("pk", DataType.TEXT)
              .addColumn("col", DataType.INT)
              .addPartitionKey("pk")
              .build());

  private DistributedStorageAdmin admin;
  private ResumableScanner scanner;
  private ResumableScannerFactory scannerFactory;

  /** The records that the scanner delivers for each table, keyed by table name. */
  private Map<String, List<Result>> tableRecords;

  @BeforeEach
  void setUp() throws Exception {
    admin = mock(DistributedStorageAdmin.class);
    scanner = mock(ResumableScanner.class);
    scannerFactory = mock(ResumableScannerFactory.class);
    tableRecords = new HashMap<>();
    when(scannerFactory.create(anyString())).thenReturn(scanner);
    when(admin.getTableMetadata(anyString(), anyString())).thenReturn(TRANSACTION_TABLE_METADATA);
    // Three tables in a fixed order.
    when(admin.getNamespaceNames()).thenReturn(new HashSet<>(Collections.singletonList("ns1")));
    when(admin.getNamespaceTableNames("ns1"))
        .thenReturn(new LinkedHashSet<>(Arrays.asList("tbl1", "tbl2", "tbl3")));
    // The scanner delivers the records of the table to the handler, like a real scanner.
    when(scanner.scan(anyString(), anyString(), any()))
        .thenAnswer(
            invocation -> {
              String table = invocation.getArgument(1);
              RecordHandler handler = invocation.getArgument(2);
              List<Result> records = tableRecords.getOrDefault(table, Collections.emptyList());
              for (Result record : records) {
                handler.handle(record);
              }
              return new ScanResult(records.size());
            });
  }

  private LedgerVerifyOrchestrator newOrchestrator() {
    return newOrchestrator(null);
  }

  private LedgerVerifyOrchestrator newOrchestrator(
      @Nullable NamespaceMappingValidator namespaceMappingValidator) {
    return new LedgerVerifyOrchestrator(
        admin, scannerFactory, CoordinatorStateAccessor.NAMESPACE, namespaceMappingValidator);
  }

  private static Properties multiStorageProperties() {
    Properties props = new Properties();
    props.setProperty(DatabaseConfig.STORAGE, MultiStorageConfig.STORAGE_NAME);
    props.setProperty(MultiStorageConfig.STORAGES, "cosmos_db,other_db");
    props.setProperty(MultiStorageConfig.STORAGES + ".cosmos_db.storage", "cosmos");
    props.setProperty(MultiStorageConfig.STORAGES + ".other_db.storage", "jdbc");
    props.setProperty(MultiStorageConfig.NAMESPACE_MAPPING, "ns:other_db");
    props.setProperty(MultiStorageConfig.DEFAULT_STORAGE, "cosmos_db");
    return props;
  }

  @Test
  void create_nonCosmosStorageGiven_shouldThrowScalarDlCleanupException() {
    // Arrange
    Properties props = new Properties();
    props.setProperty(DatabaseConfig.STORAGE, "cassandra");

    // Act & Assert
    assertThatThrownBy(() -> LedgerVerifyOrchestrator.create(props))
        .isInstanceOf(ScalarDlCleanupException.class)
        .hasMessageContaining("not supported");
  }

  @Test
  void create_jdbcTransactionManagerGiven_shouldThrowScalarDlCleanupException() {
    // Arrange
    Properties props = new Properties();
    props.setProperty(DatabaseConfig.STORAGE, "cosmos");
    props.setProperty(DatabaseConfig.TRANSACTION_MANAGER, "jdbc");

    // Act & Assert
    assertThatThrownBy(() -> LedgerVerifyOrchestrator.create(props))
        .isInstanceOf(ScalarDlCleanupException.class)
        .hasMessageContaining(ScalarDlCleanupError.UNSUPPORTED_TRANSACTION_MANAGER.buildCode());
  }

  @Test
  void create_multiStorageWithTableMappingGiven_shouldThrowScalarDlCleanupException() {
    // Arrange
    Properties props = multiStorageProperties();
    props.setProperty(MultiStorageConfig.TABLE_MAPPING, "ns2.tbl:other_db");

    // Act & Assert
    assertThatThrownBy(() -> LedgerVerifyOrchestrator.create(props))
        .isInstanceOf(ScalarDlCleanupException.class)
        .hasMessageContaining(
            ScalarDlCleanupError.MULTI_STORAGE_TABLE_MAPPING_NOT_SUPPORTED.buildCode());
  }

  @Test
  void create_cosmosStorageGiven_shouldCreateWithoutTransactionManager() {
    // Arrange
    Properties props = new Properties();
    props.setProperty(DatabaseConfig.STORAGE, "cosmos");

    StorageFactory storageFactory = mock(StorageFactory.class);
    when(storageFactory.getStorageAdmin()).thenReturn(mock(DistributedStorageAdmin.class));

    try (MockedStatic<StorageFactory> storageFactoryStatic = mockStatic(StorageFactory.class);
        MockedStatic<TransactionFactory> transactionFactoryStatic =
            mockStatic(TransactionFactory.class)) {
      storageFactoryStatic
          .when(() -> StorageFactory.create(any(Properties.class)))
          .thenReturn(storageFactory);

      // Act
      LedgerVerifyOrchestrator orchestrator = LedgerVerifyOrchestrator.create(props);

      // Assert
      assertThat(orchestrator).isNotNull();
      transactionFactoryStatic.verifyNoInteractions();
    }
  }

  @Test
  void create_multiStorageWithNonMultiStorageAdminGiven_shouldThrowAndCloseAdmin() {
    // Arrange
    Properties props = multiStorageProperties();

    DistributedStorageAdmin storageAdmin = mock(DistributedStorageAdmin.class);
    StorageFactory storageFactory = mock(StorageFactory.class);
    when(storageFactory.getStorageAdmin()).thenReturn(storageAdmin);

    try (MockedStatic<StorageFactory> storageFactoryStatic = mockStatic(StorageFactory.class)) {
      storageFactoryStatic
          .when(() -> StorageFactory.create(any(Properties.class)))
          .thenReturn(storageFactory);

      // Act & Assert
      assertThatThrownBy(() -> LedgerVerifyOrchestrator.create(props))
          .isInstanceOf(IllegalStateException.class);
      verify(storageAdmin).close();
    }
  }

  @Test
  void execute_onlyTerminalRecordsGiven_shouldScanEveryTableAndReturnPassed() throws Exception {
    // Arrange
    tableRecords.put("tbl1", Arrays.asList(VerifyTestRecords.committed()));
    tableRecords.put(
        "tbl2",
        Arrays.asList(
            VerifyTestRecords.committed(),
            VerifyTestRecords.record(TransactionState.ABORTED, 500L)));

    // Act
    VerificationResult result = newOrchestrator().execute();

    // Assert
    assertThat(result).isEqualTo(VerificationResult.PASSED);
    verify(scanner).scan(eq("ns1"), eq("tbl1"), any());
    verify(scanner).scan(eq("ns1"), eq("tbl2"), any());
    verify(scanner).scan(eq("ns1"), eq("tbl3"), any());
    verify(scanner, times(3)).close();
  }

  @Test
  void execute_preparedRecordInSecondTableGiven_shouldReturnFailedWithoutScanningThirdTable()
      throws Exception {
    // Arrange
    tableRecords.put("tbl1", Arrays.asList(VerifyTestRecords.committed()));
    tableRecords.put(
        "tbl2", Arrays.asList(VerifyTestRecords.committed(), VerifyTestRecords.prepared()));

    // Act
    VerificationResult result = newOrchestrator().execute();

    // Assert
    assertThat(result).isEqualTo(VerificationResult.FAILED);
    verify(scanner).scan(eq("ns1"), eq("tbl1"), any());
    verify(scanner).scan(eq("ns1"), eq("tbl2"), any());
    verify(scanner, never()).scan(eq("ns1"), eq("tbl3"), any());
    verify(scanner, times(2)).close();
  }

  @Test
  void execute_deletedRecordGiven_shouldReturnFailed() throws Exception {
    // Arrange
    tableRecords.put(
        "tbl3", Arrays.asList(VerifyTestRecords.record(TransactionState.DELETED, 500L)));

    // Act
    VerificationResult result = newOrchestrator().execute();

    // Assert
    assertThat(result).isEqualTo(VerificationResult.FAILED);
  }

  @Test
  void execute_recordPreparedInFutureGiven_shouldReturnFailed() throws Exception {
    // Arrange
    long future = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1);
    tableRecords.put(
        "tbl1", Arrays.asList(VerifyTestRecords.record(TransactionState.PREPARED, future)));

    // Act
    VerificationResult result = newOrchestrator().execute();

    // Assert
    assertThat(result).isEqualTo(VerificationResult.FAILED);
  }

  @Test
  void execute_twoRunsGiven_shouldScanEveryTableFromStartInBothRuns() throws Exception {
    // Arrange
    tableRecords.put("tbl1", Arrays.asList(VerifyTestRecords.committed()));

    // Act
    VerificationResult first = newOrchestrator().execute();
    VerificationResult second = newOrchestrator().execute();

    // Assert
    assertThat(first).isEqualTo(VerificationResult.PASSED);
    assertThat(second).isEqualTo(VerificationResult.PASSED);
    verify(scanner, times(2)).scan(eq("ns1"), eq("tbl1"), any());
    verify(scanner, times(2)).scan(eq("ns1"), eq("tbl2"), any());
    verify(scanner, times(2)).scan(eq("ns1"), eq("tbl3"), any());
    // Every scanner is one that takes no checkpoint.
    verify(scannerFactory, times(6)).create("ns1");
    verify(scannerFactory, never()).create(anyString(), any());
  }

  @Test
  void execute_runAfterDetectionGiven_shouldDetectAgain() throws Exception {
    // Arrange
    tableRecords.put("tbl2", Arrays.asList(VerifyTestRecords.prepared()));

    // Act
    VerificationResult first = newOrchestrator().execute();
    VerificationResult second = newOrchestrator().execute();

    // Assert
    assertThat(first).isEqualTo(VerificationResult.FAILED);
    assertThat(second).isEqualTo(VerificationResult.FAILED);
    verify(scanner, times(2)).scan(eq("ns1"), eq("tbl1"), any());
  }

  @Test
  void execute_scanFailureWithoutDetectionGiven_shouldPropagateException() throws Exception {
    // Arrange
    RuntimeException failure = new RuntimeException("Cosmos DB unavailable");
    when(scanner.scan(eq("ns1"), eq("tbl2"), any())).thenThrow(failure);

    // Act & Assert
    assertThatThrownBy(() -> newOrchestrator().execute()).isSameAs(failure);
    verify(scanner, never()).scan(eq("ns1"), eq("tbl3"), any());
    verify(scanner, times(2)).close();
  }

  @Test
  void execute_scanFailureAfterAnotherWorkerDetectedGiven_shouldReturnFailed() throws Exception {
    // Arrange
    // One worker detects a non-terminal record, and the scan then exits with the storage error of
    // another worker instead of the exception of the detection.
    when(scanner.scan(eq("ns1"), eq("tbl2"), any()))
        .thenAnswer(
            invocation -> {
              RecordHandler handler = invocation.getArgument(2);
              Throwable detection =
                  catchThrowable(() -> handler.handle(VerifyTestRecords.prepared()));
              RuntimeException failure = new RuntimeException("Cosmos DB unavailable");
              failure.addSuppressed(detection);
              throw failure;
            });

    // Act
    VerificationResult result = newOrchestrator().execute();

    // Assert
    assertThat(result).isEqualTo(VerificationResult.FAILED);
    verify(scanner, never()).scan(eq("ns1"), eq("tbl3"), any());
  }

  @Test
  void execute_tooFewTablesGiven_shouldThrowScalarDlCleanupException() throws Exception {
    // Arrange
    when(admin.getNamespaceTableNames("ns1"))
        .thenReturn(new HashSet<>(Collections.singletonList("tbl1")));

    // Act & Assert
    assertThatThrownBy(() -> newOrchestrator().execute())
        .isInstanceOf(ScalarDlCleanupException.class)
        .hasMessageContaining(ScalarDlCleanupError.TOO_FEW_TARGET_TABLES.buildCode());
    verify(scanner, never()).scan(anyString(), anyString(), any());
  }

  @Test
  void execute_namespaceMappingValidationFailureGiven_shouldThrowWithoutScanning()
      throws Exception {
    // Arrange
    NamespaceMappingValidator validator = mock(NamespaceMappingValidator.class);
    doThrow(
            new ScalarDlCleanupException(
                ScalarDlCleanupError.MULTI_STORAGE_UNREACHABLE_NAMESPACES_FOUND,
                "other_db",
                Collections.singleton("ns2")))
        .when(validator)
        .validate();

    // Act & Assert
    assertThatThrownBy(() -> newOrchestrator(validator).execute())
        .isInstanceOf(ScalarDlCleanupException.class)
        .hasMessageContaining(
            ScalarDlCleanupError.MULTI_STORAGE_UNREACHABLE_NAMESPACES_FOUND.buildCode());
    verify(admin, never()).getNamespaceNames();
    verify(scanner, never()).scan(anyString(), anyString(), any());
  }

  @Test
  void execute_unmappedNamespaceInOtherStorageGiven_shouldThrowSameErrorAsFinalize()
      throws Exception {
    // Arrange
    // The other storage has a transactional table in a namespace that the mapping does not have,
    // so the discovery through the multi-storage admin would not find it.
    DistributedStorageAdmin defaultAdmin = mock(DistributedStorageAdmin.class);
    when(defaultAdmin.getNamespaceNames()).thenReturn(Collections.singleton("ns1"));
    DistributedStorageAdmin otherAdmin = mock(DistributedStorageAdmin.class);
    when(otherAdmin.getNamespaceNames()).thenReturn(Collections.singleton("ns2"));
    when(otherAdmin.getNamespaceTableNames("ns2")).thenReturn(Collections.singleton("tbl"));
    when(otherAdmin.getTableMetadata("ns2", "tbl")).thenReturn(TRANSACTION_TABLE_METADATA);
    Map<String, DistributedStorageAdmin> storageAdmins = new HashMap<>();
    storageAdmins.put("cosmos_db", defaultAdmin);
    storageAdmins.put("other_db", otherAdmin);
    NamespaceMappingValidator validator =
        new NamespaceMappingValidator(storageAdmins, Collections.emptyMap(), "cosmos_db");

    // Act & Assert
    assertThatThrownBy(() -> newOrchestrator(validator).execute())
        .isInstanceOf(ScalarDlCleanupException.class)
        .hasMessageContaining(
            ScalarDlCleanupError.MULTI_STORAGE_UNREACHABLE_NAMESPACES_FOUND.buildCode())
        .hasMessageContaining("ns2");
    verify(scanner, never()).scan(anyString(), anyString(), any());
  }

  @Test
  void close_shouldCloseAdmin() {
    // Arrange
    LedgerVerifyOrchestrator orchestrator = newOrchestrator();

    // Act
    orchestrator.close();

    // Assert
    verify(admin).close();
  }
}
