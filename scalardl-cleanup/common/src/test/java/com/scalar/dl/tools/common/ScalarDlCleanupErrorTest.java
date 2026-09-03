package com.scalar.dl.tools.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ScalarDlCleanupErrorTest {

  @Test
  void values_shouldNotHaveDuplicateErrorCodes() {
    assertThat(Arrays.stream(ScalarDlCleanupError.values()).map(ScalarDlCleanupError::buildCode))
        .doesNotHaveDuplicates();
  }

  @Test
  void values_shouldAllHaveThreeDigitIds() {
    // The constructor already calls validate(), so simply constructing every value asserts that the
    // id length is 3. This test makes the intent explicit.
    for (ScalarDlCleanupError error : ScalarDlCleanupError.values()) {
      assertThat(error.getId()).hasSize(3);
      assertThat(error.getComponentName()).isEqualTo("DL-TOOLS");
    }
  }

  @Test
  void buildCode_userErrorGiven_shouldBuildCorrectCode() {
    // Arrange
    ScalarDlCleanupError error = ScalarDlCleanupError.COMPLETION_TOKEN_CRC_MISMATCH;

    // Act
    String code = error.buildCode();

    // Assert
    assertThat(code).isEqualTo("DL-TOOLS-1001");
  }

  @Test
  void buildCode_internalErrorGiven_shouldBuildCorrectCode() {
    // Arrange
    ScalarDlCleanupError error = ScalarDlCleanupError.STATE_LOAD_FAILED;

    // Act
    String code = error.buildCode();

    // Assert
    assertThat(code).isEqualTo("DL-TOOLS-2001");
  }

  @Test
  void values_shouldAllHaveSolution() {
    for (ScalarDlCleanupError error : ScalarDlCleanupError.values()) {
      assertThat(error.getSolution()).isNotEmpty();
    }
  }

  @Test
  void buildMessage_noArgsGiven_shouldPrefixCodeAndAppendSolution() {
    // Arrange
    ScalarDlCleanupError error = ScalarDlCleanupError.BOTH_COMPLETION_TOKENS_REQUIRED;

    // Act
    String message = error.buildMessage();

    // Assert
    assertThat(message)
        .isEqualTo(
            "DL-TOOLS-1004: Both Ledger and Auditor completion tokens are required for the "
                + "initial run. Provide both the Ledger and Auditor completion tokens emitted by "
                + "the finalization commands.");
  }

  @Test
  void buildMessage_argsGiven_shouldFormatMessageAndAppendSolution() {
    // Arrange
    ScalarDlCleanupError error = ScalarDlCleanupError.UNKNOWN_SERVER_TYPE;

    // Act
    String message = error.buildMessage("foo");

    // Assert
    assertThat(message)
        .isEqualTo(
            "DL-TOOLS-1003: Unknown server type in the completion token: foo. Verify that the "
                + "completion token was copied verbatim and is not truncated or altered.");
  }

  @Test
  void buildMessage_solutionEmptyGiven_shouldNotAppendTrailingSpace() {
    // Arrange
    ScalarDlToolsError error = new NoSolutionError();

    // Act
    String message = error.buildMessage();

    // Assert
    assertThat(message).isEqualTo("DL-TOOLS-1999: Something went wrong.");
  }

  /** A {@link ScalarDlToolsError} with no solution, which no {@link ScalarDlCleanupError} is. */
  private static final class NoSolutionError implements ScalarDlToolsError {
    @Override
    public String getComponentName() {
      return "DL-TOOLS";
    }

    @Override
    public Category getCategory() {
      return Category.USER_ERROR;
    }

    @Override
    public String getId() {
      return "999";
    }

    @Override
    public String getMessage() {
      return "Something went wrong.";
    }

    @Override
    public String getCause() {
      return "";
    }

    @Override
    public String getSolution() {
      return "";
    }
  }
}
