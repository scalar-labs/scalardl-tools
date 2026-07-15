package com.scalar.dl.tools.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scalar.dl.tools.common.Category;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// The Charset overloads errorprone's JdkObsolete recommends are Java 10+ and do not compile under
// this module's --release 8 target, so the tests use the "UTF-8" String overloads deliberately.
@SuppressWarnings("JdkObsolete")
public class ScalarDlCleanupTest {

  private static final ObjectMapper mapper = new ObjectMapper();

  private final PrintStream originalOut = System.out;
  private ByteArrayOutputStream out;

  @TempDir private Path tempDir;

  private CapturingAppender logAppender;
  private Logger commonOptionsLogger;

  @BeforeEach
  void setUp() throws Exception {
    out = new ByteArrayOutputStream();
    System.setOut(new PrintStream(out, true, "UTF-8"));

    // Capture what CommonOptions logs so the --stacktrace behavior can be asserted. slf4j delegates
    // to the same underlying log4j2 logger, so an appender attached here sees CommonOptions'
    // events.
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

  private JsonNode captured() throws Exception {
    return mapper.readTree(out.toString("UTF-8"));
  }

  private void assertUserErrorJson() throws Exception {
    JsonNode json = captured();
    assertThat(json.get("status_code").asText()).isEqualTo(Category.USER_ERROR.name());
    assertThat(json.get("error_message").isNull()).isFalse();
    assertThat(json.get("error_message").asText()).isNotBlank();
  }

  /**
   * Runs {@code finalize-ledger} against a non-existent properties file so it fails before reaching
   * any orchestrator.
   */
  private int executeFailingLedgerCommand(boolean disableStacktrace) {
    String missingProperties = tempDir.resolve("missing.properties").toString();
    String checkpoint = tempDir.toString();
    if (disableStacktrace) {
      return ScalarDlCleanup.createCommandLine()
          .execute(
              "finalize-ledger",
              "--no-stacktrace",
              "--properties",
              missingProperties,
              "--checkpoint-dir",
              checkpoint);
    }
    return ScalarDlCleanup.createCommandLine()
        .execute(
            "finalize-ledger", "--properties", missingProperties, "--checkpoint-dir", checkpoint);
  }

  @Test
  void execute_noSubcommandGiven_shouldEmitUserErrorJsonAndReturnOne() throws Exception {
    // Act
    int exitCode = ScalarDlCleanup.createCommandLine().execute();

    // Assert
    assertThat(exitCode).isEqualTo(1);
    assertUserErrorJson();
  }

  @Test
  void execute_unknownSubcommandGiven_shouldEmitUserErrorJsonAndReturnOne() throws Exception {
    // Act
    int exitCode = ScalarDlCleanup.createCommandLine().execute("no-such-command");

    // Assert
    assertThat(exitCode).isEqualTo(1);
    assertUserErrorJson();
  }

  @Test
  void execute_missingRequiredOptionGiven_shouldEmitUserErrorJsonAndReturnOne() throws Exception {
    // Act
    int exitCode = ScalarDlCleanup.createCommandLine().execute("finalize-ledger");

    // Assert
    assertThat(exitCode).isEqualTo(1);
    assertUserErrorJson();
  }

  @Test
  void execute_unknownOptionGiven_shouldEmitUserErrorJsonAndReturnOne() throws Exception {
    // Act
    int exitCode =
        ScalarDlCleanup.createCommandLine()
            .execute(
                "finalize-ledger",
                "--properties",
                "/path/to/server.properties",
                "--checkpoint-dir",
                "/data/ckpt",
                "--no-such-flag");

    // Assert
    assertThat(exitCode).isEqualTo(1);
    assertUserErrorJson();
  }

  @Test
  void execute_nonexistentPropertiesFileGiven_shouldReturnUserErrorAndLogStackTrace()
      throws Exception {
    // Act
    int exitCode = executeFailingLedgerCommand(false);

    // Assert
    assertThat(exitCode).isEqualTo(1);
    assertUserErrorJson();
    assertThat(logAppender.captured()).isTrue();
  }

  @Test
  void execute_noStacktraceGiven_shouldNotLogStackTraceWhenFailure() throws Exception {
    // Act
    int exitCode = executeFailingLedgerCommand(true);

    // Assert
    assertThat(exitCode).isEqualTo(1);
    assertUserErrorJson();
    assertThat(logAppender.captured()).isFalse();
  }

  @Test
  void execute_versionGiven_shouldPrintVersionAndReturnZero() throws Exception {
    // Act
    int exitCode = ScalarDlCleanup.createCommandLine().execute("--version");

    // Assert
    String expectedVersion = String.join(" ", new VersionProvider().getVersion());
    assertThat(exitCode).isZero();
    assertThat(out.toString("UTF-8")).contains(expectedVersion);
  }

  /** A log4j2 appender that records the formatted message of every {@code ERROR} event. */
  private static final class CapturingAppender extends AbstractAppender {

    private final List<String> errorMessages = new ArrayList<>();

    CapturingAppender() {
      super("CapturingAppender", null, null, true, Property.EMPTY_ARRAY);
    }

    @Override
    public void append(LogEvent event) {
      if (Level.ERROR.equals(event.getLevel())) {
        errorMessages.add(event.getMessage().getFormattedMessage());
      }
    }

    boolean captured() {
      return errorMessages.contains("The command failed with an exception.");
    }
  }
}
