package com.scalar.dl.tools.cli;

import com.scalar.dl.tools.common.ScalarDlCleanupError;
import com.scalar.dl.tools.common.ScalarDlCleanupException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import picocli.CommandLine.Option;

/**
 * Base class for the coordinator state deletion tool subcommands, which keep their progress in a
 * checkpoint directory. It resolves the {@code --checkpoint-dir} and delegates to {@link
 * #execute(Properties, Path)}.
 */
public abstract class AbstractToolCommand extends AbstractCommand {

  @Option(
      names = {"--checkpoint-dir"},
      required = true,
      paramLabel = "CHECKPOINT_DIR",
      description = "Directory to persist resumable-scan state.")
  protected String checkpointDir;

  @Override
  final Integer run(Properties props) throws Exception {
    return execute(props, resolveCheckpointDirectory());
  }

  private Path resolveCheckpointDirectory() {
    try {
      return Paths.get(checkpointDir);
    } catch (Exception e) {
      throw new ScalarDlCleanupException(
          ScalarDlCleanupError.INVALID_CHECKPOINT_DIRECTORY, e, checkpointDir);
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
