package com.scalar.dl.tools.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scalar.dl.tools.cleanup.CoordinatorCleanupOrchestrator;
import com.scalar.dl.tools.common.ScalarDlCleanupError;
import com.scalar.dl.tools.common.ScalarDlCleanupException;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import picocli.CommandLine;

public class CoordinatorStateCleanupCommandTest {

  private static final ObjectMapper mapper = new ObjectMapper();
  private final PrintStream originalOut = System.out;
  @TempDir Path tempDir;
  private ByteArrayOutputStream out;

  static Stream<Arguments> missingTokenCases() {
    return Stream.of(
        Arguments.of(new String[] {}, null, null),
        Arguments.of(new String[] {"--ledger-token", "ledger-tok"}, "ledger-tok", null),
        Arguments.of(new String[] {"--auditor-token", "auditor-tok"}, null, "auditor-tok"));
  }

  @BeforeEach
  void setUp() throws Exception {
    out = new ByteArrayOutputStream();
    System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
  }

  @AfterEach
  void tearDown() {
    System.setOut(originalOut);
  }

  private String propertiesFile() throws Exception {
    Path file = tempDir.resolve("ledger.properties");
    Files.write(file, "scalar.db.storage=cosmos\n".getBytes(StandardCharsets.UTF_8));
    return file.toString();
  }

  private JsonNode captured() throws Exception {
    return mapper.readTree(out.toString(StandardCharsets.UTF_8));
  }

  @Test
  void call_whenSucceeds_shouldPrintOkAndReturnZero() throws Exception {
    // Arrange
    CoordinatorCleanupOrchestrator orchestrator = mock(CoordinatorCleanupOrchestrator.class);
    AtomicReference<String> ledger = new AtomicReference<>();
    AtomicReference<String> auditor = new AtomicReference<>();
    CoordinatorStateCleanupCommand command =
        new CoordinatorStateCleanupCommand() {
          @Override
          CoordinatorCleanupOrchestrator createOrchestrator(
              Properties props, Path checkpointDir, String ledgerToken, String auditorToken) {
            ledger.set(ledgerToken);
            auditor.set(auditorToken);
            return orchestrator;
          }
        };

    // Act
    int code =
        new CommandLine(command)
            .execute(
                "--properties",
                propertiesFile(),
                "--ledger-token",
                "ledger-tok",
                "--auditor-token",
                "auditor-tok",
                "--checkpoint-dir",
                tempDir.toString());

    // Assert
    assertThat(code).isZero();
    assertThat(ledger.get()).isEqualTo("ledger-tok");
    assertThat(auditor.get()).isEqualTo("auditor-tok");
    verify(orchestrator).execute();
    verify(orchestrator).close();
    JsonNode json = captured();
    assertThat(json.get("status_code").asText()).isEqualTo("OK");
    assertThat(json.get("output").isNull()).isTrue();
  }

  @ParameterizedTest
  @MethodSource("missingTokenCases")
  void call_whenTokenMissingOnFirstRun_shouldPrintErrorAndReturnOne(
      String[] tokenArgs, String expectedLedger, String expectedAuditor) throws Exception {
    // Arrange
    CoordinatorCleanupOrchestrator orchestrator = mock(CoordinatorCleanupOrchestrator.class);
    // On a fresh run the orchestrator rejects a run that is missing either token.
    doThrow(new ScalarDlCleanupException(ScalarDlCleanupError.BOTH_COMPLETION_TOKENS_REQUIRED))
        .when(orchestrator)
        .execute();
    AtomicReference<String> ledger = new AtomicReference<>();
    AtomicReference<String> auditor = new AtomicReference<>();
    CoordinatorStateCleanupCommand command =
        new CoordinatorStateCleanupCommand() {
          @Override
          CoordinatorCleanupOrchestrator createOrchestrator(
              Properties props, Path checkpointDir, String ledgerToken, String auditorToken) {
            ledger.set(ledgerToken);
            auditor.set(auditorToken);
            return orchestrator;
          }
        };
    List<String> args = new ArrayList<>(Arrays.asList("--properties", propertiesFile()));
    args.addAll(Arrays.asList(tokenArgs));
    args.addAll(Arrays.asList("--checkpoint-dir", tempDir.toString()));

    // Act
    int code = new CommandLine(command).execute(args.toArray(new String[0]));

    // Assert
    assertThat(code).isEqualTo(1);
    assertThat(ledger.get()).isEqualTo(expectedLedger);
    assertThat(auditor.get()).isEqualTo(expectedAuditor);
    JsonNode json = captured();
    assertThat(json.get("status_code").asText()).isEqualTo("USER_ERROR");
    verify(orchestrator).close();
  }

  @Test
  void call_whenTokenMissingOnResumedRun_shouldPrintOkAndReturnZero() throws Exception {
    // Arrange
    // A resumed run reuses the checkpoint's deletion boundary, so the orchestrator succeeds even
    // when no tokens are supplied; the CLI must not reject the missing tokens itself.
    CoordinatorCleanupOrchestrator orchestrator = mock(CoordinatorCleanupOrchestrator.class);
    AtomicReference<String> ledger = new AtomicReference<>();
    AtomicReference<String> auditor = new AtomicReference<>();
    CoordinatorStateCleanupCommand command =
        new CoordinatorStateCleanupCommand() {
          @Override
          CoordinatorCleanupOrchestrator createOrchestrator(
              Properties props, Path checkpointDir, String ledgerToken, String auditorToken) {
            ledger.set(ledgerToken);
            auditor.set(auditorToken);
            return orchestrator;
          }
        };

    // Act
    int code =
        new CommandLine(command)
            .execute("--properties", propertiesFile(), "--checkpoint-dir", tempDir.toString());

    // Assert
    assertThat(code).isZero();
    assertThat(ledger.get()).isNull();
    assertThat(auditor.get()).isNull();
    verify(orchestrator).execute();
    verify(orchestrator).close();
    JsonNode json = captured();
    assertThat(json.get("status_code").asText()).isEqualTo("OK");
    assertThat(json.get("output").isNull()).isTrue();
  }

  @Test
  void call_whenFails_shouldPrintErrorAndReturnOne() throws Exception {
    // Arrange
    CoordinatorCleanupOrchestrator orchestrator = mock(CoordinatorCleanupOrchestrator.class);
    doThrow(new RuntimeException("boom")).when(orchestrator).execute();
    CoordinatorStateCleanupCommand command =
        new CoordinatorStateCleanupCommand() {
          @Override
          CoordinatorCleanupOrchestrator createOrchestrator(
              Properties props, Path checkpointDir, String ledgerToken, String auditorToken) {
            return orchestrator;
          }
        };

    // Act
    int code =
        new CommandLine(command)
            .execute(
                "--properties",
                propertiesFile(),
                "--ledger-token",
                "ledger-tok",
                "--auditor-token",
                "auditor-tok",
                "--checkpoint-dir",
                tempDir.toString());

    // Assert
    assertThat(code).isEqualTo(1);
    JsonNode json = captured();
    assertThat(json.get("status_code").asText()).isEqualTo("INTERNAL_ERROR");
    assertThat(json.get("error_message").asText()).isEqualTo("boom");
    verify(orchestrator).close();
  }
}
