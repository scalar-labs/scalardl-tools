package com.scalar.dl.tools.cleanup;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Checkpoint state for {@code auditor-finalize-records}, persisted as {@code state.json}.
 *
 * <p>It holds the start timestamp, the list of logical namespaces to sweep, and the set of
 * namespaces whose {@code asset_lock} table has already been finalized. The namespace list is
 * captured on the first invocation and reused on resumption, so a resumed run sweeps the same set
 * of namespaces and skips the ones already finalized.
 */
public final class AuditorFinalizeState {

  @JsonProperty("started_at_ms")
  private final long startedAtMs;

  @JsonProperty("namespace_list")
  private final List<String> namespaceList;

  @JsonProperty("completed_namespaces")
  private final Set<String> completedNamespaces;

  /** Creates an initial state with no completed namespaces. */
  public AuditorFinalizeState(long startedAtMs, List<String> namespaceList) {
    this(startedAtMs, namespaceList, null);
  }

  @JsonCreator
  public AuditorFinalizeState(
      @JsonProperty("started_at_ms") long startedAtMs,
      @JsonProperty("namespace_list") List<String> namespaceList,
      @JsonProperty("completed_namespaces") List<String> completedNamespaces) {
    this.startedAtMs = startedAtMs;
    this.namespaceList = namespaceList != null ? new ArrayList<>(namespaceList) : new ArrayList<>();
    this.completedNamespaces =
        completedNamespaces != null
            ? new LinkedHashSet<>(completedNamespaces)
            : new LinkedHashSet<>();
  }

  public long getStartedAtMs() {
    return startedAtMs;
  }

  public List<String> getNamespaceList() {
    return Collections.unmodifiableList(namespaceList);
  }

  public Set<String> getCompletedNamespaces() {
    return Collections.unmodifiableSet(completedNamespaces);
  }

  public void markNamespaceCompleted(String namespace) {
    completedNamespaces.add(namespace);
  }
}
