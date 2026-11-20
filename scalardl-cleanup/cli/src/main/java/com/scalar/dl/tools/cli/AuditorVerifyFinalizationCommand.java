package com.scalar.dl.tools.cli;

import com.google.common.annotations.VisibleForTesting;
import com.scalar.dl.tools.cleanup.AuditorVerifyOrchestrator;
import com.scalar.dl.tools.common.VerificationResult;
import java.util.Properties;
import picocli.CommandLine.Command;

/**
 * {@code verify-auditor-finalization}: scans the {@code asset_lock} table of every namespace from
 * the beginning and checks that no asset lock is still held for writing. It only reads the
 * database, does not connect to the Auditor, and keeps no checkpoint.
 */
@Command(
    name = "verify-auditor-finalization",
    description = {
      "Verify that no asset lock is still held for writing in any asset_lock table.",
      "Read-only. Exits with code 2 as soon as it finds such a lock.",
      "Run it after stopping the clients; it reports the locks of transactions in progress too."
    })
public class AuditorVerifyFinalizationCommand extends AbstractVerifyCommand {

  @Override
  protected VerificationResult verify(Properties props) throws Exception {
    try (AuditorVerifyOrchestrator orchestrator = createOrchestrator(props)) {
      return orchestrator.execute();
    }
  }

  @Override
  protected String failureMessage() {
    return "An asset lock held for writing was found. See the log for the lock."
        + " Run 'finalize-auditor' from scratch, then run this command again.";
  }

  @VisibleForTesting
  AuditorVerifyOrchestrator createOrchestrator(Properties props) {
    return AuditorVerifyOrchestrator.create(props);
  }
}
