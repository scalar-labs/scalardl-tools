package com.scalar.dl.tools.cli;

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
    name = "coordinator-state-deleter",
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

  public static void main(String[] args) {
    int exitCode = new CommandLine(new CoordinatorStateDeleter()).execute(args);
    System.exit(exitCode);
  }
}
