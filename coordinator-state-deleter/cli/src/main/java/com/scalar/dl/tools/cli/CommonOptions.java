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

  // The finalize/cleanup commands are heavyweight, state-mutating operations that an operator
  // cannot cheaply re-run, so by default a failure surfaces the full stack trace immediately rather
  // than forcing an opt-in re-run just to obtain it; --no-stacktrace opts out of that output.
  @Option(
      names = {"--no-stacktrace"},
      description = "Do not output the Java stack trace to stderr on failure.")
  protected boolean stacktraceSuppressed;

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
   * Logs the full stack trace of the given exception unless {@code --no-stacktrace} is set. The
   * error message itself is always emitted separately via {@link Common#printError(Exception)}.
   *
   * @param e the exception to log
   */
  protected void logStackTrace(Exception e) {
    if (!stacktraceSuppressed) {
      logger.error("The command failed with an exception.", e);
    }
  }
}
