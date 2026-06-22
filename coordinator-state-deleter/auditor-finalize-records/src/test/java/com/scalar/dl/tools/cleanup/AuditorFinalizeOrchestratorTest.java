package com.scalar.dl.tools.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.scalar.db.api.DistributedStorage;
import com.scalar.db.api.DistributedStorageAdmin;
import com.scalar.db.api.Result;
import com.scalar.db.api.Scan;
import com.scalar.db.api.Scanner;
import com.scalar.dl.client.service.AuditorClient;
import com.scalar.dl.tools.common.CompletionToken;
import com.scalar.dl.tools.scan.ResumableScanner;
import com.scalar.dl.tools.scan.ResumableScannerFactory;
import com.scalar.dl.tools.scan.ScanResult;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@SuppressWarnings("resource")
class AuditorFinalizeOrchestratorTest {

  private static final String NAMESPACE = "auditor";

  @TempDir Path tempDir;

  private DistributedStorageAdmin admin;
  private DistributedStorage storage;
  private AuditorClient auditorClient;
  private ResumableScanner scanner;
  private ResumableScannerFactory scannerFactory;

  @BeforeEach
  void setUp() {
    admin = mock(DistributedStorageAdmin.class);
    storage = mock(DistributedStorage.class);
    auditorClient = mock(AuditorClient.class);
    scanner = mock(ResumableScanner.class);
    scannerFactory = mock(ResumableScannerFactory.class);
    when(scannerFactory.create(any())).thenReturn(scanner);
  }

  /** Creates a mock namespace {@link Result} with the given name. */
  private Result createMockNamespaceResult(String name) {
    Result result = mock(Result.class);
    when(result.getText(AuditorInternalValues.NAMESPACE_COLUMN_NAME)).thenReturn(name);
    return result;
  }

  /** Creates a mock {@link Scanner} that iterates over the given results. */
  private Scanner createMockScanner(Result... results) {
    Scanner mockScanner = mock(Scanner.class);
    Iterator<Result> iterator = Arrays.asList(results).iterator();
    when(mockScanner.iterator()).thenReturn(iterator);
    return mockScanner;
  }

  private AuditorFinalizeOrchestrator orchestrator() {
    return new AuditorFinalizeOrchestrator(
        admin, storage, auditorClient, scannerFactory, tempDir, NAMESPACE);
  }

  @Test
  void execute_initialRunGiven_shouldScanReturnTokenAndPersistState() throws Exception {
    // Arrange — no namespace registry table, so only the default namespace is swept.
    when(admin.tableExists(NAMESPACE, AuditorInternalValues.NAMESPACE_TABLE)).thenReturn(false);
    when(scanner.scan(anyString(), anyString(), any())).thenReturn(new ScanResult(10));

    // Act — the start timestamp is captured as now() - the lock validity period.
    long before = System.currentTimeMillis() - AuditorInternalValues.LOCK_VALID_PERIOD_MS;
    String completionToken = orchestrator().execute();
    long after = System.currentTimeMillis() - AuditorInternalValues.LOCK_VALID_PERIOD_MS;

    // Assert
    CompletionToken token = CompletionToken.decode(completionToken);
    assertThat(token.getServerType()).isEqualTo(CompletionToken.ServerType.AUDITOR);
    assertThat(token.getStartedAtMs()).isBetween(before, after);

    verify(scanner).scan(eq(NAMESPACE), eq("asset_lock"), any());
    verify(scanner).close();

    // State is persisted with the default namespace completed and the token's start timestamp.
    AuditorFinalizeState state = new AuditorFinalizeStateManager(tempDir).load();
    assertThat(state).isNotNull();
    assertThat(state.getCompletedNamespaces()).containsExactly("default");
    assertThat(state.getStartedAtMs()).isEqualTo(token.getStartedAtMs());
  }

  @Test
  void execute_namespaceTableExistsGiven_shouldScanAllRegisteredNamespaces() throws Exception {
    // Arrange
    when(admin.tableExists(NAMESPACE, AuditorInternalValues.NAMESPACE_TABLE)).thenReturn(true);
    Scanner namespaceScanner =
        createMockScanner(createMockNamespaceResult("ns1"), createMockNamespaceResult("ns2"));
    when(storage.scan(any(Scan.class))).thenReturn(namespaceScanner);
    when(scanner.scan(anyString(), anyString(), any())).thenReturn(new ScanResult(0));

    // Act
    orchestrator().execute();

    // Assert — default namespace (physical = base) + two registered namespaces, each resolved to
    // its physical namespace (<base>_<ns>), should be scanned, and nothing else.
    verify(scanner).scan(eq(NAMESPACE), eq("asset_lock"), any());
    verify(scanner).scan(eq("auditor_ns1"), eq("asset_lock"), any());
    verify(scanner).scan(eq("auditor_ns2"), eq("asset_lock"), any());
    verify(scanner, times(3)).scan(anyString(), anyString(), any());
  }

  @Test
  void execute_scanFailureGiven_shouldPropagateException() throws Exception {
    // Arrange — only the default namespace, whose scan fails.
    when(admin.tableExists(NAMESPACE, AuditorInternalValues.NAMESPACE_TABLE)).thenReturn(false);
    when(scanner.scan(eq(NAMESPACE), eq("asset_lock"), any()))
        .thenThrow(new RuntimeException("Cosmos DB unavailable"));

    // Act & Assert
    assertThatThrownBy(() -> orchestrator().execute())
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("Cosmos DB unavailable");

    verify(scanner).close();
  }

  @Test
  void execute_scanFailureGiven_shouldNotMarkCompleted() throws Exception {
    // Arrange — default + ns1; the default succeeds, ns1 fails.
    when(admin.tableExists(NAMESPACE, AuditorInternalValues.NAMESPACE_TABLE)).thenReturn(true);
    Scanner namespaceScanner = createMockScanner(createMockNamespaceResult("ns1"));
    when(storage.scan(any(com.scalar.db.api.Scan.class))).thenReturn(namespaceScanner);
    when(scanner.scan(eq(NAMESPACE), eq("asset_lock"), any())).thenReturn(new ScanResult(10));
    when(scanner.scan(eq("auditor_ns1"), eq("asset_lock"), any()))
        .thenThrow(new RuntimeException("Cosmos DB unavailable"));

    // Act
    assertThatThrownBy(() -> orchestrator().execute()).isInstanceOf(RuntimeException.class);

    // Assert — only the finished namespace is marked completed; the failed one is left out.
    AuditorFinalizeState state = new AuditorFinalizeStateManager(tempDir).load();
    assertThat(state).isNotNull();
    assertThat(state.getCompletedNamespaces()).containsExactly("default");
  }

  @Test
  void execute_resumedStateGiven_shouldSkipCompletedNamespacesAndProcessRemaining()
      throws Exception {
    // Arrange — pre-persist a checkpoint where the default namespace is already completed.
    long startedAtMs = 1000L;
    AuditorFinalizeStateManager stateManager = new AuditorFinalizeStateManager(tempDir);
    stateManager.persist(
        new AuditorFinalizeState(
            startedAtMs, Arrays.asList("default", "ns1"), Collections.singletonList("default")));
    when(scanner.scan(anyString(), anyString(), any())).thenReturn(new ScanResult(5));

    // Act
    String token = orchestrator().execute();

    // Assert — the completed default namespace is skipped, only ns1 is scanned, and the original
    // start timestamp is carried over across the resume.
    verify(scanner, never()).scan(eq(NAMESPACE), eq("asset_lock"), any());
    verify(scanner).scan(eq("auditor_ns1"), eq("asset_lock"), any());
    assertThat(CompletionToken.decode(token).getStartedAtMs()).isEqualTo(startedAtMs);

    // The newly processed namespace is persisted alongside the pre-completed one.
    AuditorFinalizeState finalState = new AuditorFinalizeStateManager(tempDir).load();
    assertThat(finalState).isNotNull();
    assertThat(finalState.getCompletedNamespaces()).containsExactlyInAnyOrder("default", "ns1");
  }

  @Test
  void close_shouldCloseAdminAndStorageAndAuditorClient() {
    // Arrange
    AuditorFinalizeOrchestrator orchestrator = orchestrator();

    // Act
    orchestrator.close();

    // Assert
    verify(admin).close();
    verify(storage).close();
    verify(auditorClient).shutdown();
  }
}
