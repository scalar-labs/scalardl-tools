package com.scalar.dl.tools.cli;

import com.scalar.dl.tools.common.ScalarDlCleanupError;
import com.scalar.dl.tools.common.ScalarDlCleanupException;
import java.util.Properties;
import java.util.concurrent.Callable;

/**
 * Base class for every subcommand. It loads the {@code --properties} file, delegates to {@link
 * #run(Properties)}, and translates any failure into the standard error JSON and a non-zero exit
 * code.
 */
public abstract class AbstractCommand extends CommonOptions implements Callable<Integer> {

  /** Exit code returned on success. */
  public static final int SUCCESS_EXIT_CODE = 0;

  /** Exit code returned on any failure. */
  public static final int FAILURE_EXIT_CODE = 1;

  @Override
  public final Integer call() {
    try {
      Properties props = loadConfiguration();
      return run(props);
    } catch (Exception e) {
      Common.printError(e);
      logStackTrace(e);
      return FAILURE_EXIT_CODE;
    }
  }

  private Properties loadConfiguration() {
    try {
      return loadProperties();
    } catch (Exception e) {
      throw new ScalarDlCleanupException(
          ScalarDlCleanupError.CONFIGURATION_LOAD_FAILED, e, properties);
    }
  }

  /**
   * Runs the command against the loaded configuration.
   *
   * @param props the loaded properties
   * @return the exit code (0 on success)
   * @throws Exception if the command fails
   */
  abstract Integer run(Properties props) throws Exception;
}
