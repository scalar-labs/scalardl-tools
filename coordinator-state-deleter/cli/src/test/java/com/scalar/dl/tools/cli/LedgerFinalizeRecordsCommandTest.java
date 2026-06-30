package com.scalar.dl.tools.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scalar.dl.tools.cleanup.LedgerFinalizeOrchestrator;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

public class LedgerFinalizeRecordsCommandTest {

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
    Path file = tempDir.resolve("ledger.properties");
    Files.write(file, "scalar.db.storage=cosmos\n".getBytes(StandardCharsets.UTF_8));
    return file.toString();
  }

  private JsonNode captured() throws Exception {
    return mapper.readTree(out.toString("UTF-8"));
  }

  @Test
  void call_whenSucceeds_shouldPrintTokenAndReturnZero() throws Exception {
    // Arrange
    LedgerFinalizeOrchestrator orchestrator = mock(LedgerFinalizeOrchestrator.class);
    when(orchestrator.execute()).thenReturn("ledger-token");
    LedgerFinalizeRecordsCommand command =
        new LedgerFinalizeRecordsCommand() {
          @Override
          LedgerFinalizeOrchestrator createOrchestrator(Properties props, Path checkpointDir) {
            return orchestrator;
          }
        };

    // Act
    int code =
        new CommandLine(command)
            .execute("--properties", propertiesFile(), "--checkpoint-dir", tempDir.toString());

    // Assert
    assertThat(code).isZero();
    JsonNode json = captured();
    assertThat(json.get("output").get("completion_token").asText()).isEqualTo("ledger-token");
    verify(orchestrator).close();
  }

  @Test
  void call_whenFails_shouldPrintErrorAndReturnOne() throws Exception {
    // Arrange
    LedgerFinalizeOrchestrator orchestrator = mock(LedgerFinalizeOrchestrator.class);
    when(orchestrator.execute()).thenThrow(new RuntimeException("boom"));
    LedgerFinalizeRecordsCommand command =
        new LedgerFinalizeRecordsCommand() {
          @Override
          LedgerFinalizeOrchestrator createOrchestrator(Properties props, Path checkpointDir) {
            return orchestrator;
          }
        };

    // Act
    int code = new CommandLine(command).execute("--properties", propertiesFile());

    // Assert
    assertThat(code).isEqualTo(1);
    JsonNode json = captured();
    assertThat(json.get("status_code").asText()).isEqualTo("INTERNAL_ERROR");
    assertThat(json.get("error_message").asText()).isEqualTo("boom");
    verify(orchestrator).close();
  }
}
