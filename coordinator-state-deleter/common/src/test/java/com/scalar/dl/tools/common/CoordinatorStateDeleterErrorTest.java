package com.scalar.dl.tools.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class CoordinatorStateDeleterErrorTest {

  @Test
  void values_shouldNotHaveDuplicateErrorCodes() {
    assertThat(
            Arrays.stream(CoordinatorStateDeleterError.values())
                .map(CoordinatorStateDeleterError::buildCode))
        .doesNotHaveDuplicates();
  }

  @Test
  void values_shouldAllHaveThreeDigitIds() {
    // The constructor already calls validate(), so simply constructing every value asserts that the
    // id length is 3. This test makes the intent explicit.
    for (CoordinatorStateDeleterError error : CoordinatorStateDeleterError.values()) {
      assertThat(error.getId()).hasSize(3);
      assertThat(error.getComponentName()).isEqualTo("DL-TOOLS");
    }
  }

  @Test
  void buildCode_userErrorGiven_shouldBuildCorrectCode() {
    // Arrange
    CoordinatorStateDeleterError error = CoordinatorStateDeleterError.COMPLETION_TOKEN_CRC_MISMATCH;

    // Act
    String code = error.buildCode();

    // Assert
    assertThat(code).isEqualTo("DL-TOOLS-1001");
  }

  @Test
  void buildCode_internalErrorGiven_shouldBuildCorrectCode() {
    // Arrange
    CoordinatorStateDeleterError error = CoordinatorStateDeleterError.STATE_LOAD_FAILED;

    // Act
    String code = error.buildCode();

    // Assert
    assertThat(code).isEqualTo("DL-TOOLS-2001");
  }

  @Test
  void buildMessage_noArgsGiven_shouldPrefixCodeToMessage() {
    // Arrange
    CoordinatorStateDeleterError error =
        CoordinatorStateDeleterError.BOTH_COMPLETION_TOKENS_REQUIRED;

    // Act
    String message = error.buildMessage();

    // Assert
    assertThat(message)
        .isEqualTo(
            "DL-TOOLS-1004: Both ledger and auditor completion tokens are required for the "
                + "initial run.");
  }

  @Test
  void buildMessage_argsGiven_shouldFormatMessage() {
    // Arrange
    CoordinatorStateDeleterError error = CoordinatorStateDeleterError.UNKNOWN_SERVER_TYPE;

    // Act
    String message = error.buildMessage("foo");

    // Assert
    assertThat(message)
        .isEqualTo("DL-TOOLS-1003: Unknown server type in the completion token: foo.");
  }
}
