package com.scalar.dl.tools.cli;

import com.google.common.annotations.VisibleForTesting;
import com.scalar.dl.tools.cleanup.AuditorFinalizeOrchestrator;
import com.scalar.dl.tools.cleanup.RequestProofCleanupOrchestrator;
import java.nio.file.Path;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code finalize-auditor}: a single Auditor-side command that runs two phases in sequence.
 *
 * <ol>
 *   <li>Finalize every unreleased asset lock across all {@code asset_lock} tables, producing a
 *       completion token.
 *   <li>Using that token's guarantee timestamp, delete every settled record from the {@code
 *       request_proof} table. This phase is skipped when {@code --skip-request-proof-cleanup} is
 *       specified.
 * </ol>
 */
@Command(
    name = "finalize-auditor",
    description = {
      "Finalize unreleased asset locks, clean up settled request proof records, and emit a completion token.",
      "Hand the emitted token to the Ledger operator for the 'cleanup-coordinator' command."
    })
public class AuditorFinalizeRecordsCommand extends AbstractToolCommand {
  // This is deliberately the only command that composes two orchestrators. The CLI is the sole
  // consumer, so the two-phase Auditor workflow is sequenced here rather than extracted into a
  // shared composing orchestrator.

  private static final Logger logger = LoggerFactory.getLogger(AuditorFinalizeRecordsCommand.class);

  // Deliberately not persisted to the checkpoint: re-running without this option on the same
  // checkpoint directory regenerates the identical token and then runs the cleanup phase against
  // the boundary fixed by the first run.
  @Option(
      names = {"--skip-request-proof-cleanup"},
      description = {
        "Skip the request proof cleanup and leave the request proof records as they are.",
        "The completion token is still emitted."
      })
  private boolean requestProofCleanupSkipped;

  @Override
  protected Integer execute(Properties props, Path checkpointDir) throws Exception {
    // The single --properties file serves as both the Auditor's database configuration and the
    // source of the Auditor server connection used to reach the privileged RecoverAssetLock RPC.
    String auditorToken;
    try (AuditorFinalizeOrchestrator orchestrator =
        createFinalizeOrchestrator(props, checkpointDir)) {
      auditorToken = orchestrator.execute();
    }

    if (requestProofCleanupSkipped) {
      logger.info("Skipping the request proof cleanup because it was requested.");
      Common.printOutput(Common.completionTokenOutput(auditorToken));
      return SUCCESS_EXIT_CODE;
    }

    // The token is printed only after this cleanup phase succeeds. A cleanup failure here does not
    // lose the token: it is derived deterministically from the startedAtMs that
    // AuditorFinalizeOrchestrator persists to the checkpoint directory. Re-running this
    // command with the same checkpoint directory reloads that startedAtMs and regenerates the
    // identical token.
    try (RequestProofCleanupOrchestrator cleanup =
        createRequestProofCleanupOrchestrator(props, checkpointDir, auditorToken)) {
      cleanup.execute();
    }

    Common.printOutput(Common.completionTokenOutput(auditorToken));
    return SUCCESS_EXIT_CODE;
  }

  @VisibleForTesting
  AuditorFinalizeOrchestrator createFinalizeOrchestrator(Properties props, Path checkpointDir) {
    return AuditorFinalizeOrchestrator.create(props, checkpointDir);
  }

  @VisibleForTesting
  RequestProofCleanupOrchestrator createRequestProofCleanupOrchestrator(
      Properties props, Path checkpointDir, String auditorToken) {
    return RequestProofCleanupOrchestrator.create(props, checkpointDir, auditorToken);
  }
}
