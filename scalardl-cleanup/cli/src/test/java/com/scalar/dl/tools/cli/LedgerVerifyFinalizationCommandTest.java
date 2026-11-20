package com.scalar.dl.tools.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scalar.dl.tools.cleanup.LedgerVerifyOrchestrator;
import com.scalar.dl.tools.common.ScalarDlCleanupError;
import com.scalar.dl.tools.common.ScalarDlCleanupException;
import com.scalar.dl.tools.common.VerificationResult;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

public class LedgerVerifyFinalizationCommandTest {

  private static final ObjectMapper mapper = new ObjectMapper();
  private final PrintStream originalOut = System.out;
  @TempDir Path tempDir;
  private ByteArrayOutputStream out;
  private CapturingAppender logAppender;
  private Logger commonOptionsLogger;

  @BeforeEach
  void setUp() throws Exception {
    out = new ByteArrayOutputStream();
    System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));

    // CommonOptions logs the stack trace of a failure; capture it to assert its absence.
    logAppender = new CapturingAppender();
    logAppender.start();
    commonOptionsLogger = (Logger) LogManager.getLogger(CommonOptions.class);
    commonOptionsLogger.addAppender(logAppender);
  }

  @AfterEach
  void tearDown() {
    System.setOut(originalOut);
    commonOptionsLogger.removeAppender(logAppender);
    logAppender.stop();
  }

  private String propertiesFile() throws Exception {
    Path file = tempDir.resolve("ledger.properties");
    Files.write(file, "scalar.db.storage=cosmos\n".getBytes(StandardCharsets.UTF_8));
    return file.toString();
  }

  private String[] outputLines() {
    return out.toString(StandardCharsets.UTF_8).trim().split("\\R");
  }

  private JsonNode captured() throws Exception {
    return mapper.readTree(out.toString(StandardCharsets.UTF_8));
  }

  private LedgerVerifyFinalizationCommand commandWith(LedgerVerifyOrchestrator orchestrator) {
    return new LedgerVerifyFinalizationCommand() {
      @Override
      LedgerVerifyOrchestrator createOrchestrator(Properties props) {
        return orchestrator;
      }
    };
  }

  @Test
  void call_whenVerificationPasses_shouldPrintOkAndReturnZero() throws Exception {
    // Arrange
    LedgerVerifyOrchestrator orchestrator = mock(LedgerVerifyOrchestrator.class);
    when(orchestrator.execute()).thenReturn(VerificationResult.PASSED);

    // Act
    int code = new CommandLine(commandWith(orchestrator)).execute("--properties", propertiesFile());

    // Assert
    assertThat(code).isZero();
    assertThat(outputLines()).hasSize(1);
    JsonNode json = captured();
    assertThat(json.get("status_code").asText()).isEqualTo("OK");
    assertThat(json.get("output").isNull()).isTrue();
    verify(orchestrator).close();
  }

  @Test
  void call_whenVerificationFails_shouldPrintVerificationFailedAndReturnTwoWithoutStackTrace()
      throws Exception {
    // Arrange
    LedgerVerifyOrchestrator orchestrator = mock(LedgerVerifyOrchestrator.class);
    when(orchestrator.execute()).thenReturn(VerificationResult.FAILED);

    // Act
    int code = new CommandLine(commandWith(orchestrator)).execute("--properties", propertiesFile());

    // Assert
    assertThat(code).isEqualTo(2);
    assertThat(outputLines()).hasSize(1);
    JsonNode json = captured();
    assertThat(json.get("status_code").asText()).isEqualTo("VERIFICATION_FAILED");
    assertThat(json.get("error_message").asText()).contains("finalize-ledger");
    assertThat(logAppender.events).isEmpty();
    verify(orchestrator).close();
  }

  @Test
  void call_whenOrchestratorReturnsNoVerdict_shouldPrintInternalErrorAndReturnOne()
      throws Exception {
    // Arrange
    LedgerVerifyOrchestrator orchestrator = mock(LedgerVerifyOrchestrator.class);
    when(orchestrator.execute()).thenReturn(null);

    // Act
    int code = new CommandLine(commandWith(orchestrator)).execute("--properties", propertiesFile());

    // Assert
    assertThat(code).isEqualTo(1);
    assertThat(outputLines()).hasSize(1);
    assertThat(captured().get("status_code").asText()).isEqualTo("INTERNAL_ERROR");
    verify(orchestrator).close();
  }

  @Test
  void call_whenOrchestratorThrowsCleanupException_shouldPrintErrorAndReturnOne() throws Exception {
    // Arrange
    LedgerVerifyOrchestrator orchestrator = mock(LedgerVerifyOrchestrator.class);
    when(orchestrator.execute())
        .thenThrow(new ScalarDlCleanupException(ScalarDlCleanupError.TOO_FEW_TARGET_TABLES, 1));

    // Act
    int code = new CommandLine(commandWith(orchestrator)).execute("--properties", propertiesFile());

    // Assert
    assertThat(code).isEqualTo(1);
    assertThat(outputLines()).hasSize(1);
    JsonNode json = captured();
    assertThat(json.get("status_code").asText())
        .isEqualTo(ScalarDlCleanupError.TOO_FEW_TARGET_TABLES.getCategory().name());
    assertThat(json.get("error_message").asText())
        .contains(ScalarDlCleanupError.TOO_FEW_TARGET_TABLES.buildCode());
    verify(orchestrator).close();
  }

  @Test
  void execute_checkpointDirGiven_shouldEmitUserErrorJsonAndReturnOne() throws Exception {
    // Arrange Act
    int code =
        ScalarDlCleanup.createCommandLine()
            .execute(
                "verify-ledger-finalization",
                "--properties",
                propertiesFile(),
                "--checkpoint-dir",
                tempDir.toString());

    // Assert
    assertThat(code).isEqualTo(1);
    JsonNode json = captured();
    assertThat(json.get("status_code").asText()).isEqualTo("USER_ERROR");
    assertThat(json.get("error_message").asText()).contains("--checkpoint-dir");
  }

  @Test
  void execute_propertiesNotGiven_shouldEmitUserErrorJsonAndReturnOne() throws Exception {
    // Arrange Act
    int code = ScalarDlCleanup.createCommandLine().execute("verify-ledger-finalization");

    // Assert
    assertThat(code).isEqualTo(1);
    JsonNode json = captured();
    assertThat(json.get("status_code").asText()).isEqualTo("USER_ERROR");
    assertThat(json.get("error_message").asText()).contains("--properties");
  }

  @Test
  void usage_shouldNotOfferCheckpointDir() {
    // Arrange Act
    String usage =
        ScalarDlCleanup.createCommandLine()
            .getSubcommands()
            .get("verify-ledger-finalization")
            .getUsageMessage();

    // Assert
    assertThat(usage).contains("--properties").doesNotContain("--checkpoint-dir");
  }

  private static final class CapturingAppender extends AbstractAppender {
    private final List<LogEvent> events = new ArrayList<>();

    private CapturingAppender() {
      super("capturing", null, null, true, Property.EMPTY_ARRAY);
    }

    @Override
    public void append(LogEvent event) {
      events.add(event.toImmutable());
    }
  }
}
