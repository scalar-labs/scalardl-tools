package com.scalar.dl.tools.cleanup;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Checkpoint state for {@code ledger-finalize-records}. Stored as {@code state.json}. */
public final class LedgerFinalizeState {

  @JsonProperty("started_at_ms")
  private final long startedAtMs;

  @JsonProperty("table_list")
  private final List<String> tableList;

  @JsonProperty("completed_tables")
  private final Set<String> completedTables;

  @JsonCreator
  public LedgerFinalizeState(
      @JsonProperty("started_at_ms") long startedAtMs,
      @JsonProperty("table_list") List<String> tableList,
      @JsonProperty("completed_tables") List<String> completedTables) {
    this.startedAtMs = startedAtMs;
    this.tableList = tableList != null ? new ArrayList<>(tableList) : new ArrayList<>();
    this.completedTables =
        completedTables != null ? new LinkedHashSet<>(completedTables) : new LinkedHashSet<>();
  }

  public long getStartedAtMs() {
    return startedAtMs;
  }

  public List<String> getTableList() {
    return Collections.unmodifiableList(tableList);
  }

  public Set<String> getCompletedTables() {
    return Collections.unmodifiableSet(completedTables);
  }

  public void markTableCompleted(String qualifiedTableName) {
    completedTables.add(qualifiedTableName);
  }
}
