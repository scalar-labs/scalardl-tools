package com.scalar.dl.tools.cli;

import com.google.common.annotations.VisibleForTesting;
import com.scalar.dl.tools.common.Category;
import com.scalar.dl.tools.common.CoordinatorStateDeleterException;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.HelpCommand;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;

/**
 * Top-level command for the coordinator-state-deletion tools. It groups the operator-facing
 * subcommands into a single binary.
 */
@Command(
    name = "scalardl-cleanup",
    mixinStandardHelpOptions = true,
    versionProvider = VersionProvider.class,
    subcommands = {
      LedgerFinalizeRecordsCommand.class,
      AuditorFinalizeRecordsCommand.class,
      CoordinatorStateCleanupCommand.class,
      HelpCommand.class,
    },
    description =
        "Tools to safely reclaim space by removing stale ScalarDB coordinator-state and "
            + "ScalarDL Auditor records.")
public class CoordinatorStateDeleter implements Runnable {

  @Spec
  @SuppressWarnings("unused")
  private CommandSpec spec;

  /** Invoked only when no subcommand is given; a subcommand is mandatory. */
  @Override
  public void run() {
    throw new ParameterException(spec.commandLine(), "Missing required subcommand.");
  }

  /**
   * Builds the top-level {@link CommandLine}, installing a parameter-exception handler so that
   * command-line parse failures are reported on the stdout JSON channel like runtime failures.
   */
  @VisibleForTesting
  static CommandLine createCommandLine() {
    return new CommandLine(new CoordinatorStateDeleter())
        .setParameterExceptionHandler(
            (ex, args) -> {
              // The default parameter-exception handler emits human-readable text on stderr with
              // exit code 2 and nothing on stdout, which a consumer cannot distinguish from a
              // legitimate "output": null success. Route the parse failure to the JSON channel as a
              // USER_ERROR instead.
              Common.printError(
                  new CoordinatorStateDeleterException(ex.getMessage(), Category.USER_ERROR));
              return AbstractToolCommand.FAILURE_EXIT_CODE;
            });
  }

  public static void main(String[] args) {
    int exitCode = createCommandLine().execute(args);
    System.exit(exitCode);
  }
}
