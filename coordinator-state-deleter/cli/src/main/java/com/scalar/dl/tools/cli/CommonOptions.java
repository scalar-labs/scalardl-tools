package com.scalar.dl.tools.cli;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Option;

/** Options shared by every subcommand. */
public class CommonOptions {

  private static final Logger logger = LoggerFactory.getLogger(CommonOptions.class);

  @Option(
      names = {"--properties"},
      required = true,
      paramLabel = "PROPERTIES_FILE",
      description = "A configuration file in properties format.")
  protected String properties;

  @Option(
      names = {"--checkpoint-dir"},
      required = true,
      paramLabel = "CHECKPOINT_DIR",
      description = "Directory to persist resumable-scan state.")
  protected String checkpointDir;

  @Option(
      names = {"--stacktrace"},
      description = "Output the Java stack trace to stderr on failure.")
  protected boolean stacktraceEnabled;

  @Option(
      names = {"-h", "--help"},
      usageHelp = true,
      description = "Display the help message.")
  boolean helpRequested;

  /** Loads the {@code --properties} file into a {@link Properties} object. */
  protected Properties loadProperties() throws IOException {
    Properties props = new Properties();
    try (InputStream in = Files.newInputStream(Paths.get(properties))) {
      props.load(in);
    }
    return props;
  }

  /**
   * Logs the full stack trace of the given exception when {@code --stacktrace} is set. The error
   * message itself is always emitted separately via {@link Common#printError(Exception)}.
   *
   * @param e the exception to log
   */
  protected void logStackTrace(Exception e) {
    if (stacktraceEnabled) {
      logger.error("The command failed with an exception.", e);
    }
  }
}
