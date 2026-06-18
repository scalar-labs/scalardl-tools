package com.scalar.dl.tools.cleanup;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Checkpoint state for {@code coordinator-state-cleanup}. Stored as {@code state.json}.
 *
 * <p>Contains the deletable-before timestamp (the earlier of the two guarantee timestamps) and a
 * flag indicating whether the cleanup has already completed. The {@code completed} flag lets a
 * re-invocation against a finished checkpoint short-circuit instead of re-scanning the table.
 */
public final class CoordinatorCleanupState {

  @JsonProperty("deletable_before_ms")
  private final long deletableBeforeMs;

  @JsonProperty("completed")
  private boolean completed;

  @JsonCreator
  public CoordinatorCleanupState(
      @JsonProperty("deletable_before_ms") long deletableBeforeMs,
      @JsonProperty("completed") boolean completed) {
    this.deletableBeforeMs = deletableBeforeMs;
    this.completed = completed;
  }

  /** Creates a state for a new run that has not completed yet. */
  public CoordinatorCleanupState(long deletableBeforeMs) {
    this(deletableBeforeMs, false);
  }

  @JsonProperty("deletable_before_ms")
  public long getDeletableBeforeMs() {
    return deletableBeforeMs;
  }

  @JsonProperty("completed")
  public boolean isCompleted() {
    return completed;
  }

  /** Marks the cleanup as completed. */
  public void markCompleted() {
    this.completed = true;
  }
}
