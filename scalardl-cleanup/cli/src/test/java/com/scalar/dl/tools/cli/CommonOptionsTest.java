package com.scalar.dl.tools.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import picocli.CommandLine.MissingParameterException;

public class CommonOptionsTest {

  @Test
  void parseArgs_givenNoCheckpointDir_shouldThrowMissingParameter() {
    // Act & Assert
    assertThatThrownBy(
            () ->
                new CommandLine(new CoordinatorStateCleanupCommand())
                    .parseArgs("--properties", "/path/to/server.properties"))
        .isInstanceOf(MissingParameterException.class);
  }

  @Test
  void parseArgs_givenCheckpointDir_shouldSetCheckpointDir() {
    // Arrange
    LedgerFinalizeRecordsCommand command = new LedgerFinalizeRecordsCommand();

    // Act
    new CommandLine(command)
        .parseArgs("--properties", "/path/to/server.properties", "--checkpoint-dir", "/data/ckpt");

    // Assert
    assertThat(command.checkpointDir).isEqualTo("/data/ckpt");
  }

  @Test
  void parseArgs_givenMissingProperties_shouldThrowMissingParameter() {
    // Arrange

    // Act & Assert
    assertThatThrownBy(() -> new CommandLine(new LedgerFinalizeRecordsCommand()).parseArgs())
        .isInstanceOf(MissingParameterException.class);
  }
}
