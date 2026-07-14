package com.scalar.dl.tools.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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

import com.scalar.db.api.DistributedStorageAdmin;
import com.scalar.db.api.DistributedTransactionManager;
import com.scalar.db.config.DatabaseConfig;
import com.scalar.db.service.StorageFactory;
import com.scalar.db.service.TransactionFactory;
import com.scalar.db.transaction.consensuscommit.CoordinatorStateAccessor;
import com.scalar.dl.tools.common.CompletionToken;
import com.scalar.dl.tools.common.CoordinatorStateDeleterException;
import com.scalar.dl.tools.scan.ResumableScanner;
import com.scalar.dl.tools.scan.ResumableScannerFactory;
import com.scalar.dl.tools.scan.ScanResult;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

@SuppressWarnings("resource")
class LedgerFinalizeOrchestratorTest {

  @TempDir Path tempDir;

  private DistributedStorageAdmin admin;
  private DistributedTransactionManager txManager;
  private ResumableScanner scanner;
  private ResumableScannerFactory scannerFactory;

  @BeforeEach
  void setUp() {
    admin = mock(DistributedStorageAdmin.class);
    txManager = mock(DistributedTransactionManager.class);
    scanner = mock(ResumableScanner.class);
    scannerFactory = mock(ResumableScannerFactory.class);
    when(scannerFactory.create(any())).thenReturn(scanner);
  }

  private LedgerFinalizeOrchestrator newOrchestrator() {
    return newOrchestrator(CoordinatorStateAccessor.NAMESPACE);
  }

  private LedgerFinalizeOrchestrator newOrchestrator(String coordinatorNamespace) {
    return new LedgerFinalizeOrchestrator(
        admin, txManager, scannerFactory, tempDir, coordinatorNamespace);
  }

  @Test
  void create_nonCosmosStorageGiven_shouldThrowCoordinatorStateDeleterException() {
    // Arrange
    Properties props = new Properties();
    props.setProperty(DatabaseConfig.STORAGE, "cassandra");

    // Act & Assert
    assertThatThrownBy(() -> LedgerFinalizeOrchestrator.create(props, tempDir))
        .isInstanceOf(CoordinatorStateDeleterException.class)
        .hasMessageContaining("not supported");
  }

  @Test
  void create_jdbcTransactionManagerGiven_shouldThrowCoordinatorStateDeleterException() {
    // Arrange
    Properties props = new Properties();
    props.setProperty(DatabaseConfig.STORAGE, "cosmos");
    props.setProperty(DatabaseConfig.TRANSACTION_MANAGER, "jdbc");

    // Act & Assert
    assertThatThrownBy(() -> LedgerFinalizeOrchestrator.create(props, tempDir))
        .isInstanceOf(CoordinatorStateDeleterException.class)
        .hasMessageContaining("jdbc")
        .hasMessageContaining("not supported");
  }

  @Test
  void create_cosmosStorageGiven_shouldNotThrow() {
    // Arrange
    Properties props = new Properties();
    props.setProperty(DatabaseConfig.STORAGE, "cosmos");

    StorageFactory storageFactory = mock(StorageFactory.class);
    when(storageFactory.getStorageAdmin()).thenReturn(mock(DistributedStorageAdmin.class));
    TransactionFactory transactionFactory = mock(TransactionFactory.class);
    when(transactionFactory.getTransactionManager())
        .thenReturn(mock(DistributedTransactionManager.class));

    try (MockedStatic<StorageFactory> storageFactoryStatic = mockStatic(StorageFactory.class);
        MockedStatic<TransactionFactory> transactionFactoryStatic =
            mockStatic(TransactionFactory.class)) {
      storageFactoryStatic
          .when(() -> StorageFactory.create(any(Properties.class)))
          .thenReturn(storageFactory);
      transactionFactoryStatic
          .when(() -> TransactionFactory.create(any(Properties.class)))
          .thenReturn(transactionFactory);

      // Act & Assert
      assertThatCode(() -> LedgerFinalizeOrchestrator.create(props, tempDir))
          .doesNotThrowAnyException();
    }
  }

  @Test
  void execute_initialRunGiven_shouldDiscoverTablesScanAndReturnToken() throws Exception {
    // Arrange
    when(admin.getNamespaceNames()).thenReturn(new HashSet<>(Arrays.asList("ns1", "ns2")));
    when(admin.getNamespaceTableNames("ns1"))
        .thenReturn(new HashSet<>(Collections.singletonList("tbl1")));
    when(admin.getNamespaceTableNames("ns2"))
        .thenReturn(new HashSet<>(Collections.singletonList("tbl2")));
    when(scanner.scan(anyString(), anyString(), any())).thenReturn(new ScanResult(10));

    LedgerFinalizeOrchestrator orchestrator = newOrchestrator();

    // Act
    long before = System.currentTimeMillis();
    String completionToken = orchestrator.execute();
    long after = System.currentTimeMillis();

    // Assert
    CompletionToken token = CompletionToken.decode(completionToken);
    assertThat(token.getServerType()).isEqualTo(CompletionToken.ServerType.LEDGER);
    assertThat(token.getStartedAtMs()).isBetween(before, after);

    // Each discovered table is scanned and the scanner is closed per table.
    verify(scanner).scan(eq("ns1"), eq("tbl1"), any());
    verify(scanner).scan(eq("ns2"), eq("tbl2"), any());
    verify(scanner, times(2)).close();

    LedgerFinalizeState state = new LedgerFinalizeStateManager(tempDir).load();
    assertThat(state).isNotNull();
    assertThat(state.getCompletedTables()).containsExactlyInAnyOrder("ns1.tbl1", "ns2.tbl2");
    assertThat(state.getStartedAtMs()).isEqualTo(token.getStartedAtMs());
  }

  @Test
  void execute_defaultCoordinatorNamespaceGiven_shouldExcludeCoordinatorTableFromScanning()
      throws Exception {
    // Arrange
    when(admin.getNamespaceNames())
        .thenReturn(new HashSet<>(Arrays.asList(CoordinatorStateAccessor.NAMESPACE, "ns1")));
    when(admin.getNamespaceTableNames(CoordinatorStateAccessor.NAMESPACE))
        .thenReturn(new HashSet<>(Collections.singletonList(CoordinatorStateAccessor.TABLE)));
    when(admin.getNamespaceTableNames("ns1"))
        .thenReturn(new HashSet<>(Collections.singletonList("tbl1")));
    when(scanner.scan(anyString(), anyString(), any())).thenReturn(new ScanResult(10));

    LedgerFinalizeOrchestrator orchestrator = newOrchestrator();

    // Act
    orchestrator.execute();

    // Assert
    verify(scanner).scan(eq("ns1"), eq("tbl1"), any());
    verify(scanner, never())
        .scan(eq(CoordinatorStateAccessor.NAMESPACE), eq(CoordinatorStateAccessor.TABLE), any());
  }

  @Test
  void execute_customCoordinatorNamespaceGiven_shouldExcludeCoordinatorTableFromScanning()
      throws Exception {
    // Arrange
    String customNamespace = "my_coordinator";
    when(admin.getNamespaceNames())
        .thenReturn(new HashSet<>(Arrays.asList(customNamespace, "ns1")));
    when(admin.getNamespaceTableNames(customNamespace))
        .thenReturn(new HashSet<>(Collections.singletonList(CoordinatorStateAccessor.TABLE)));
    when(admin.getNamespaceTableNames("ns1"))
        .thenReturn(new HashSet<>(Collections.singletonList("tbl1")));
    when(scanner.scan(anyString(), anyString(), any())).thenReturn(new ScanResult(10));

    LedgerFinalizeOrchestrator orchestrator = newOrchestrator(customNamespace);

    // Act
    orchestrator.execute();

    // Assert
    verify(scanner).scan(eq("ns1"), eq("tbl1"), any());
    verify(scanner, never()).scan(eq(customNamespace), eq(CoordinatorStateAccessor.TABLE), any());
  }

  @Test
  void execute_noTablesGiven_shouldReturnTokenWithoutScanning() throws Exception {
    // Arrange
    when(admin.getNamespaceNames()).thenReturn(Collections.emptySet());

    LedgerFinalizeOrchestrator orchestrator = newOrchestrator();

    // Act
    String completionToken = orchestrator.execute();

    // Assert
    assertThat(completionToken).isNotEmpty();
    verify(scanner, never()).scan(anyString(), anyString(), any());
  }

  @Test
  void execute_scanFailureGiven_shouldPropagateException() throws Exception {
    // Arrange
    when(admin.getNamespaceNames()).thenReturn(new HashSet<>(Collections.singletonList("ns1")));
    when(admin.getNamespaceTableNames("ns1"))
        .thenReturn(new HashSet<>(Collections.singletonList("tbl1")));
    when(scanner.scan(eq("ns1"), eq("tbl1"), any()))
        .thenThrow(new RuntimeException("Cosmos DB unavailable"));

    LedgerFinalizeOrchestrator orchestrator = newOrchestrator();

    // Act & Assert
    assertThatThrownBy(orchestrator::execute)
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("Cosmos DB unavailable");

    verify(scanner).close();
  }

  @Test
  void execute_scanFailureGiven_shouldNotMarkCompleted() throws Exception {
    // Arrange
    // Discovers two tables, succeeds on the first, fails on the second.
    when(admin.getNamespaceNames()).thenReturn(new LinkedHashSet<>(Arrays.asList("ns1", "ns2")));
    when(admin.getNamespaceTableNames("ns1"))
        .thenReturn(new HashSet<>(Collections.singletonList("tbl1")));
    when(admin.getNamespaceTableNames("ns2"))
        .thenReturn(new HashSet<>(Collections.singletonList("tbl2")));
    when(scanner.scan(eq("ns1"), eq("tbl1"), any())).thenReturn(new ScanResult(10));
    when(scanner.scan(eq("ns2"), eq("tbl2"), any()))
        .thenThrow(new RuntimeException("Cosmos DB unavailable"));

    LedgerFinalizeOrchestrator orchestrator = newOrchestrator();

    // Act
    assertThatThrownBy(orchestrator::execute).isInstanceOf(RuntimeException.class);

    // Assert
    // Only the finished table is marked completed; the failed table is left not-completed.
    LedgerFinalizeState state = new LedgerFinalizeStateManager(tempDir).load();
    assertThat(state).isNotNull();
    assertThat(state.getCompletedTables()).containsExactly("ns1.tbl1");
  }

  @Test
  void execute_resumedStateGiven_shouldSkipCompletedTablesAndProcessRemaining() throws Exception {
    // Arrange
    // Pre-persist a checkpoint where the first of two tables is already completed.
    long startedAtMs = 1000L;
    LedgerFinalizeStateManager stateManager = new LedgerFinalizeStateManager(tempDir);
    stateManager.persist(
        new LedgerFinalizeState(
            startedAtMs,
            Arrays.asList("ns1.tbl1", "ns2.tbl2"),
            Collections.singletonList("ns1.tbl1")));
    when(scanner.scan(anyString(), anyString(), any())).thenReturn(new ScanResult(5));

    LedgerFinalizeOrchestrator orchestrator = newOrchestrator();

    // Act
    String token = orchestrator.execute();

    // Assert
    // The completed table is skipped; only the remaining table is scanned.
    verify(scanner, never()).scan(eq("ns1"), eq("tbl1"), any());
    verify(scanner).scan(eq("ns2"), eq("tbl2"), any());
    // The original start timestamp is carried over across the resume.
    assertThat(CompletionToken.decode(token).getStartedAtMs()).isEqualTo(startedAtMs);
  }

  @Test
  void close_shouldCloseAdminAndTxManager() {
    // Arrange
    LedgerFinalizeOrchestrator orchestrator = newOrchestrator();

    // Act
    orchestrator.close();

    // Assert
    verify(admin).close();
    verify(txManager).close();
  }
}
