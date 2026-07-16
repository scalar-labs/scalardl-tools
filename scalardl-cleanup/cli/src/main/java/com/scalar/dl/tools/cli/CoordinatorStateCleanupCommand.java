package com.scalar.dl.tools.cli;

import com.google.common.annotations.VisibleForTesting;
import com.scalar.dl.tools.cleanup.CoordinatorCleanupOrchestrator;
import java.nio.file.Path;
import java.util.Properties;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code cleanup-coordinator}: validates the two completion tokens, derives the deletable-before
 * boundary as the earlier of their guarantee timestamps, and deletes every coordinator-state record
 * created before it.
 *
 * <p>The tokens are required only on the first run; on a resumed run the boundary persisted in the
 * checkpoint is reused and any passed tokens are ignored (the orchestrator logs a warning).
 */
@Command(
    name = "cleanup-coordinator",
    description = "Delete coordinator-state records that are safe to remove given both tokens.")
public class CoordinatorStateCleanupCommand extends AbstractToolCommand {

  @Option(
      names = {"--ledger-token"},
      paramLabel = "LEDGER_TOKEN",
      description = "The completion token emitted by the 'finalize-ledger' command.")
  private String ledgerToken;

  @Option(
      names = {"--auditor-token"},
      paramLabel = "AUDITOR_TOKEN",
      description = "The completion token emitted by the 'finalize-auditor' command.")
  private String auditorToken;

  @Override
  protected Integer execute(Properties props, Path checkpointDir) throws Exception {
    try (CoordinatorCleanupOrchestrator orchestrator =
        createOrchestrator(props, checkpointDir, ledgerToken, auditorToken)) {
      orchestrator.execute();
    }
    // Emit the success output only after close() so a failure while releasing resources cannot
    // produce a success output followed by an error output.
    Common.printOutput(null);
    return SUCCESS_EXIT_CODE;
  }

  @VisibleForTesting
  CoordinatorCleanupOrchestrator createOrchestrator(
      Properties props, Path checkpointDir, String ledgerToken, String auditorToken) {
    return CoordinatorCleanupOrchestrator.create(props, checkpointDir, ledgerToken, auditorToken);
  }
}
