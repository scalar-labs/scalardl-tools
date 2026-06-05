package com.scalar.dl.tools.cleanup;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Checkpoint state for {@code coordinator-state-cleanup}. Stored as {@code state.json}.
 *
 * <p>Contains the deletable-before timestamp (the earlier of the two guarantee timestamps).
 */
public final class CoordinatorCleanupState {

  @JsonProperty("deletable_before_ms")
  private final long deletableBeforeMs;

  @JsonCreator
  public CoordinatorCleanupState(@JsonProperty("deletable_before_ms") long deletableBeforeMs) {
    this.deletableBeforeMs = deletableBeforeMs;
  }

  @JsonProperty("deletable_before_ms")
  public long getDeletableBeforeMs() {
    return deletableBeforeMs;
  }
}
