package com.scalar.dl.tools.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scalar.dl.tools.common.Category;
import com.scalar.dl.tools.common.ScalarDlCleanupException;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// The Charset overloads errorprone's JdkObsolete recommends are Java 10+ and do not compile under
// this module's --release 8 target, so the tests use the "UTF-8" String overloads deliberately.
@SuppressWarnings("JdkObsolete")
public class CommonTest {

  private static final ObjectMapper mapper = new ObjectMapper();

  private final PrintStream originalOut = System.out;
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

  private JsonNode captured() throws Exception {
    return mapper.readTree(out.toString("UTF-8"));
  }

  @Test
  void printOutput_givenNull_shouldEmitOkStatusWithNullOutput() throws Exception {
    // Act
    Common.printOutput(null);

    // Assert
    JsonNode json = captured();
    assertThat(json.get("status_code").asText()).isEqualTo("OK");
    assertThat(json.get("output").isNull()).isTrue();
  }

  @Test
  void printOutput_givenCompletionToken_shouldEmitTokenUnderOutput() throws Exception {
    // Arrange
    JsonNode tokenOutput = Common.completionTokenOutput("token-123");

    // Act
    Common.printOutput(tokenOutput);

    // Assert
    JsonNode json = captured();
    assertThat(json.get("status_code").asText()).isEqualTo("OK");
    assertThat(json.get("output").get("completion_token").asText()).isEqualTo("token-123");
  }

  @Test
  void printError_givenUntypedException_shouldUseInternalErrorAndKeepMessage() throws Exception {
    // Arrange
    IllegalArgumentException exception = new IllegalArgumentException("bad token");

    // Act
    Common.printError(exception);

    // Assert
    JsonNode json = captured();
    assertThat(json.get("status_code").asText()).isEqualTo("INTERNAL_ERROR");
    assertThat(json.get("error_message").asText()).isEqualTo("bad token");
  }

  @Test
  void printError_whenExceptionMessageIsNull_shouldFallBackToToString() throws Exception {
    // Arrange
    NullPointerException exception = new NullPointerException();

    // Act
    Common.printError(exception);

    // Assert
    JsonNode json = captured();
    assertThat(json.get("status_code").asText()).isEqualTo("INTERNAL_ERROR");
    assertThat(json.get("error_message").isNull()).isFalse();
    assertThat(json.get("error_message").asText()).isEqualTo(exception.toString());
  }

  @Test
  void printError_givenScalarDlCleanupException_shouldUseItsCategory() throws Exception {
    // Arrange
    ScalarDlCleanupException exception =
        new ScalarDlCleanupException("bad config", Category.USER_ERROR);

    // Act
    Common.printError(exception);

    // Assert
    JsonNode json = captured();
    assertThat(json.get("status_code").asText()).isEqualTo("USER_ERROR");
    assertThat(json.get("error_message").asText()).isEqualTo("bad config");
  }
}
