package com.scalar.dl.tools.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ScalarDlCleanupExceptionTest {

  @Test
  void constructor_errorGiven_shouldBuildMessageAndCarryCategory() {
    // Arrange & Act
    ScalarDlCleanupException exception =
        new ScalarDlCleanupException(ScalarDlCleanupError.AUDITOR_COMPLETION_TOKEN_REQUIRED);

    // Assert
    assertThat(exception.getMessage())
        .isEqualTo("DL-TOOLS-1007: The Auditor completion token is required for the initial run.");
    assertThat(exception.getCategory()).isEqualTo(Category.USER_ERROR);
  }

  @Test
  void constructor_errorWithArgsGiven_shouldFormatMessage() {
    // Arrange & Act
    ScalarDlCleanupException exception =
        new ScalarDlCleanupException(ScalarDlCleanupError.UNKNOWN_SERVER_TYPE, "foo");

    // Assert
    assertThat(exception.getMessage())
        .isEqualTo("DL-TOOLS-1003: Unknown server type in the completion token: foo.");
    assertThat(exception.getCategory()).isEqualTo(Category.USER_ERROR);
  }

  @Test
  void constructor_errorWithCauseGiven_shouldRetainCause() {
    // Arrange
    Exception cause = new IllegalStateException("boom");

    // Act
    ScalarDlCleanupException exception =
        new ScalarDlCleanupException(ScalarDlCleanupError.STATE_LOAD_FAILED, cause, "/tmp/state");

    // Assert
    assertThat(exception.getMessage())
        .isEqualTo("DL-TOOLS-2001: Failed to load the state from /tmp/state.");
    assertThat(exception.getCause()).isSameAs(cause);
    assertThat(exception.getCategory()).isEqualTo(Category.INTERNAL_ERROR);
  }
}
