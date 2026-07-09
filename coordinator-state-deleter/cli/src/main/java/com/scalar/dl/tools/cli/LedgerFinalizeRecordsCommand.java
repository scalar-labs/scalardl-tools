package com.scalar.dl.tools.cli;

import com.google.common.annotations.VisibleForTesting;
import com.scalar.dl.tools.cleanup.LedgerFinalizeOrchestrator;
import java.nio.file.Path;
import java.util.Properties;
import picocli.CommandLine.Command;

/**
 * {@code finalize-ledger}: scans every transactional table managed by the Ledger-side ScalarDB and
 * finalizes records still in a non-terminal state, then emits a completion token consumed by {@code
 * cleanup-coordinator}.
 */
@Command(
    name = "finalize-ledger",
    description = {
      "Finalize non-terminal records across all transactional tables and emit a completion token.",
      "Pass the emitted token to the 'cleanup-coordinator' command."
    })
public class LedgerFinalizeRecordsCommand extends AbstractToolCommand {

  @Override
  protected Integer execute(Properties props, Path checkpointDir) throws Exception {
    String token;
    try (LedgerFinalizeOrchestrator orchestrator = createOrchestrator(props, checkpointDir)) {
      token = orchestrator.execute();
    }
    // Emit the success output only after close() so a failure while releasing resources cannot
    // produce a success output followed by an error output.
    Common.printOutput(Common.completionTokenOutput(token));
    return SUCCESS_EXIT_CODE;
  }

  @VisibleForTesting
  LedgerFinalizeOrchestrator createOrchestrator(Properties props, Path checkpointDir) {
    return LedgerFinalizeOrchestrator.create(props, checkpointDir);
  }
}
