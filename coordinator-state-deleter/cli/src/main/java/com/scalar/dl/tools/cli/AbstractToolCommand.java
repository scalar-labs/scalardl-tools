package com.scalar.dl.tools.cli;

import com.scalar.dl.tools.common.CoordinatorStateDeleterError;
import com.scalar.dl.tools.common.CoordinatorStateDeleterException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.concurrent.Callable;

/**
 * Base class for the coordinator state deletion tool subcommands. It loads the {@code --properties}
 * file and resolves the {@code --checkpoint-dir}, delegates to {@link #execute(Properties, Path)},
 * and translates any failure into the standard error JSON and a non-zero exit code.
 */
public abstract class AbstractToolCommand extends CommonOptions implements Callable<Integer> {

  /** Exit code returned on success. */
  public static final int SUCCESS_EXIT_CODE = 0;

  /** Exit code returned on any failure. */
  public static final int FAILURE_EXIT_CODE = 1;

  @Override
  public final Integer call() {
    try {
      Properties props = loadConfiguration();
      Path checkpoint = resolveCheckpointDirectory();
      return execute(props, checkpoint);
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
      throw new CoordinatorStateDeleterException(
          CoordinatorStateDeleterError.CONFIGURATION_LOAD_FAILED, e, properties);
    }
  }

  private Path resolveCheckpointDirectory() {
    try {
      return Paths.get(checkpointDir);
    } catch (Exception e) {
      throw new CoordinatorStateDeleterException(
          CoordinatorStateDeleterError.INVALID_CHECKPOINT_DIRECTORY, e, checkpointDir);
    }
  }

  /**
   * Runs the command's orchestration against the loaded configuration.
   *
   * @param props the loaded properties
   * @param checkpointDir the resolved checkpoint directory
   * @return the exit code (0 on success)
   * @throws Exception if the orchestration fails
   */
  protected abstract Integer execute(Properties props, Path checkpointDir) throws Exception;
}
