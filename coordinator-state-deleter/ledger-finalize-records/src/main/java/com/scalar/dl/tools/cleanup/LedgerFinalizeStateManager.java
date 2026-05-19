package com.scalar.dl.tools.cleanup;

import com.scalar.dl.tools.common.StateManager;
import java.nio.file.Path;

/** Manages {@link LedgerFinalizeState} checkpoint persistence. */
public final class LedgerFinalizeStateManager extends StateManager<LedgerFinalizeState> {

  static final String SUBDIRECTORY = "ledger-finalize-records";

  public LedgerFinalizeStateManager(Path checkpointDir) {
    super(checkpointDir, SUBDIRECTORY, LedgerFinalizeState.class);
  }
}
