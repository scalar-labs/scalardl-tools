package com.scalar.dl.tools.cleanup;

import com.scalar.dl.tools.common.StateManager;
import java.nio.file.Path;

/** Manages {@link AuditorFinalizeState} checkpoint persistence. */
public final class AuditorFinalizeStateManager extends StateManager<AuditorFinalizeState> {

  static final String SUBDIRECTORY = "auditor-finalize-records";

  public AuditorFinalizeStateManager(Path checkpointDir) {
    super(checkpointDir, SUBDIRECTORY, AuditorFinalizeState.class);
  }
}
