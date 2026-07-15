package com.scalar.dl.tools.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scalar.dl.tools.cleanup.AuditorFinalizeOrchestrator;
import com.scalar.dl.tools.cleanup.RequestProofCleanupOrchestrator;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;
import picocli.CommandLine;

// The Charset overloads errorprone's JdkObsolete recommends are Java 10+ and do not compile under
// this module's --release 8 target, so the tests use the "UTF-8" String overloads deliberately.
@SuppressWarnings("JdkObsolete")
public class AuditorFinalizeRecordsCommandTest {

  private static final ObjectMapper mapper = new ObjectMapper();
  private final PrintStream originalOut = System.out;
  @TempDir Path tempDir;
  private ByteArrayOutputStream out;

  @BeforeEach
  void setUp() throws Exception {
    out = new ByteArrayOutputStream();
    System.setOut(new PrintStream(out, true, "UTF-8"));
  }

  @AfterEach
  void tearDown() {
    System.setOut(originalOut);
  }

  private String propertiesFile() throws Exception {
    Path file = tempDir.resolve("auditor.properties");
    Files.write(file, "scalar.db.storage=cosmos\n".getBytes(StandardCharsets.UTF_8));
    return file.toString();
  }

  private JsonNode captured() throws Exception {
    return mapper.readTree(out.toString("UTF-8"));
  }

  @Test
  void call_whenBothPhasesSucceed_shouldPrintTokenAndReturnZero() throws Exception {
    // Arrange
    AuditorFinalizeOrchestrator finalizer = mock(AuditorFinalizeOrchestrator.class);
    when(finalizer.execute()).thenReturn("auditor-token");
    RequestProofCleanupOrchestrator cleanup = mock(RequestProofCleanupOrchestrator.class);
    AtomicReference<String> tokenPassedToCleanup = new AtomicReference<>();
    AuditorFinalizeRecordsCommand command =
        new AuditorFinalizeRecordsCommand() {
          @Override
          AuditorFinalizeOrchestrator createFinalizeOrchestrator(
              Properties auditorProps, Properties clientProps, Path checkpointDir) {
            return finalizer;
          }

          @Override
          RequestProofCleanupOrchestrator createRequestProofCleanupOrchestrator(
              Properties props, Path checkpointDir, String auditorToken) {
            tokenPassedToCleanup.set(auditorToken);
            return cleanup;
          }
        };

    // Act
    int code =
        new CommandLine(command)
            .execute("--properties", propertiesFile(), "--checkpoint-dir", tempDir.toString());

    // Assert
    assertThat(code).isZero();
    assertThat(tokenPassedToCleanup.get()).isEqualTo("auditor-token");
    InOrder order = inOrder(finalizer, cleanup);
    order.verify(finalizer).execute();
    order.verify(cleanup).execute();
    verify(finalizer).close();
    verify(cleanup).close();
    JsonNode json = captured();
    assertThat(json.get("output").get("completion_token").asText()).isEqualTo("auditor-token");
  }

  @Test
  void call_whenFinalizeFails_shouldNotRunCleanupAndReturnOne() throws Exception {
    // Arrange
    AuditorFinalizeOrchestrator finalizer = mock(AuditorFinalizeOrchestrator.class);
    when(finalizer.execute()).thenThrow(new RuntimeException("finalize failed"));
    AtomicBoolean cleanupCreated = new AtomicBoolean(false);
    AuditorFinalizeRecordsCommand command =
        new AuditorFinalizeRecordsCommand() {
          @Override
          AuditorFinalizeOrchestrator createFinalizeOrchestrator(
              Properties auditorProps, Properties clientProps, Path checkpointDir) {
            return finalizer;
          }

          @Override
          RequestProofCleanupOrchestrator createRequestProofCleanupOrchestrator(
              Properties props, Path checkpointDir, String auditorToken) {
            cleanupCreated.set(true);
            return mock(RequestProofCleanupOrchestrator.class);
          }
        };

    // Act
    int code =
        new CommandLine(command)
            .execute("--properties", propertiesFile(), "--checkpoint-dir", tempDir.toString());

    // Assert
    assertThat(code).isEqualTo(1);
    assertThat(cleanupCreated.get()).isFalse();
    verify(finalizer).close();
    JsonNode json = captured();
    assertThat(json.get("status_code").asText()).isEqualTo("INTERNAL_ERROR");
    assertThat(json.get("error_message").asText()).isEqualTo("finalize failed");
    assertThat(out.toString("UTF-8")).doesNotContain("completion_token");
  }

  @Test
  void call_whenCleanupFails_shouldNotPrintTokenAndReturnOne() throws Exception {
    // Arrange
    AuditorFinalizeOrchestrator finalizer = mock(AuditorFinalizeOrchestrator.class);
    when(finalizer.execute()).thenReturn("auditor-token");
    RequestProofCleanupOrchestrator cleanup = mock(RequestProofCleanupOrchestrator.class);
    doThrow(new RuntimeException("cleanup failed")).when(cleanup).execute();
    AuditorFinalizeRecordsCommand command =
        new AuditorFinalizeRecordsCommand() {
          @Override
          AuditorFinalizeOrchestrator createFinalizeOrchestrator(
              Properties auditorProps, Properties clientProps, Path checkpointDir) {
            return finalizer;
          }

          @Override
          RequestProofCleanupOrchestrator createRequestProofCleanupOrchestrator(
              Properties props, Path checkpointDir, String auditorToken) {
            return cleanup;
          }
        };

    // Act
    int code =
        new CommandLine(command)
            .execute("--properties", propertiesFile(), "--checkpoint-dir", tempDir.toString());

    // Assert
    assertThat(code).isEqualTo(1);
    verify(finalizer).execute();
    verify(cleanup).execute();
    verify(finalizer).close();
    verify(cleanup).close();
    JsonNode json = captured();
    assertThat(json.get("status_code").asText()).isEqualTo("INTERNAL_ERROR");
    assertThat(json.get("error_message").asText()).isEqualTo("cleanup failed");
    assertThat(out.toString("UTF-8")).doesNotContain("completion_token");
  }
}
