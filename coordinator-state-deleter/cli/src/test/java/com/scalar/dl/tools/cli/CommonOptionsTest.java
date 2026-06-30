package com.scalar.dl.tools.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import picocli.CommandLine.MissingParameterException;

public class CommonOptionsTest {

  @Test
  void parseArgs_givenNoCheckpointDir_shouldDefaultToTmpCheckpoint() {
    // Arrange
    CoordinatorStateCleanupCommand command = new CoordinatorStateCleanupCommand();

    // Act
    new CommandLine(command).parseArgs("--properties", "/path/to/server.properties");

    // Assert
    assertThat(command.checkpointDir).isEqualTo("/tmp/checkpoint");
  }

  @Test
  void parseArgs_givenCheckpointDir_shouldOverrideDefault() {
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
