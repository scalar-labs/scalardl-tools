package com.scalar.dl.tools.cleanup;

import com.scalar.dl.tools.common.StateManager;
import java.nio.file.Path;

/** Manages {@link CoordinatorCleanupState} checkpoint persistence. */
public final class CoordinatorCleanupStateManager extends StateManager<CoordinatorCleanupState> {

  static final String SUBDIRECTORY = "coordinator-state-cleanup";

  public CoordinatorCleanupStateManager(Path checkpointDir) {
    super(checkpointDir, SUBDIRECTORY, CoordinatorCleanupState.class);
  }
}
