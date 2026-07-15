package com.scalar.dl.tools.cleanup;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Checkpoint state for {@code request-proof-cleanup}. Stored as {@code state.json}.
 *
 * <p>Contains the deletable-before timestamp (the Auditor guarantee timestamp carried by the
 * Auditor completion token) and a flag indicating whether the cleanup has already completed. The
 * {@code completed} flag lets a re-invocation against a finished checkpoint short-circuit instead
 * of re-scanning the table.
 */
public final class RequestProofCleanupState {

  @JsonProperty("deletable_before_ms")
  private final long deletableBeforeMs;

  @JsonProperty("completed")
  private boolean completed;

  @JsonCreator
  public RequestProofCleanupState(
      @JsonProperty("deletable_before_ms") long deletableBeforeMs,
      @JsonProperty("completed") boolean completed) {
    this.deletableBeforeMs = deletableBeforeMs;
    this.completed = completed;
  }

  /** Creates a state for a new run that has not completed yet. */
  public RequestProofCleanupState(long deletableBeforeMs) {
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
