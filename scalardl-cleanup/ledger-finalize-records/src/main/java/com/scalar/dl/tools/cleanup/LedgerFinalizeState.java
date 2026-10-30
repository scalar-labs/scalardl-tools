package com.scalar.dl.tools.cleanup;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Checkpoint state for {@code ledger-finalize-records}. Stored as {@code state.json}.
 *
 * <p>A table is recorded with the ID of the storage it is in, so that tables with the same name in
 * different storages are not confused.
 */
public final class LedgerFinalizeState {

  @JsonProperty("started_at_ms")
  private final long startedAtMs;

  @JsonProperty("table_list")
  private final List<TargetTable> tableList;

  @JsonProperty("completed_tables")
  private final Set<TargetTable> completedTables;

  /** Creates an initial state with no completed tables. */
  public LedgerFinalizeState(long startedAtMs, List<TargetTable> tableList) {
    this(startedAtMs, tableList, null);
  }

  @JsonCreator
  public LedgerFinalizeState(
      @JsonProperty("started_at_ms") long startedAtMs,
      @JsonProperty("table_list") List<TargetTable> tableList,
      @JsonProperty("completed_tables") List<TargetTable> completedTables) {
    this.startedAtMs = startedAtMs;
    this.tableList = tableList != null ? new ArrayList<>(tableList) : new ArrayList<>();
    this.completedTables =
        completedTables != null ? new LinkedHashSet<>(completedTables) : new LinkedHashSet<>();
  }

  public long getStartedAtMs() {
    return startedAtMs;
  }

  public List<TargetTable> getTableList() {
    return Collections.unmodifiableList(tableList);
  }

  public Set<TargetTable> getCompletedTables() {
    return Collections.unmodifiableSet(completedTables);
  }

  public void markTableCompleted(TargetTable table) {
    completedTables.add(table);
  }

  /**
   * A table to finalize, identified by the ID of the storage it is in and its fully qualified name.
   */
  public record TargetTable(
      @JsonProperty("storage_id") String storageId,
      @JsonProperty("table") String qualifiedTableName) {}
}
