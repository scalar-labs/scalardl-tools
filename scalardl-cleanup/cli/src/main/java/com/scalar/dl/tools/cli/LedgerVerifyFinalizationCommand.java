package com.scalar.dl.tools.cli;

import com.google.common.annotations.VisibleForTesting;
import com.scalar.dl.tools.cleanup.LedgerVerifyOrchestrator;
import com.scalar.dl.tools.common.VerificationResult;
import java.util.Properties;
import picocli.CommandLine.Command;

/**
 * {@code verify-ledger-finalization}: scans every transactional table managed by the Ledger-side
 * ScalarDB from the beginning and checks that no record is still in a non-terminal state. It only
 * reads the database and keeps no checkpoint.
 */
@Command(
    name = "verify-ledger-finalization",
    description = {
      "Verify that no record in any transactional table is still in a non-terminal state.",
      "Read-only. Exits with code 2 as soon as it finds such a record.",
      "Run it after stopping the clients; it reports the records of transactions in progress too."
    })
public class LedgerVerifyFinalizationCommand extends AbstractVerifyCommand {

  @Override
  protected VerificationResult verify(Properties props) throws Exception {
    try (LedgerVerifyOrchestrator orchestrator = createOrchestrator(props)) {
      return orchestrator.execute();
    }
  }

  @Override
  protected String failureMessage() {
    return "A record in a non-terminal state was found. See the log for the record."
        + " Run 'finalize-ledger' from scratch, then run this command again.";
  }

  @VisibleForTesting
  LedgerVerifyOrchestrator createOrchestrator(Properties props) {
    return LedgerVerifyOrchestrator.create(props);
  }
}
