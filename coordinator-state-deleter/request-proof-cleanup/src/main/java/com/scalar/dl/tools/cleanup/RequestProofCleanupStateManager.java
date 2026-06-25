package com.scalar.dl.tools.cleanup;

import com.scalar.dl.tools.common.StateManager;
import java.nio.file.Path;

/** Manages {@link RequestProofCleanupState} checkpoint persistence. */
public final class RequestProofCleanupStateManager extends StateManager<RequestProofCleanupState> {

  static final String SUBDIRECTORY = "request-proof-cleanup";

  public RequestProofCleanupStateManager(Path checkpointDir) {
    super(checkpointDir, SUBDIRECTORY, RequestProofCleanupState.class);
  }
}
