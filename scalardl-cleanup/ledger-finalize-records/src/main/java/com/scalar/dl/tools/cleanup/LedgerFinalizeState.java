package com.scalar.dl.tools.cleanup;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import javax.annotation.Nullable;

/** Checkpoint state for {@code ledger-finalize-records}. Stored as {@code state.json}. */
public final class LedgerFinalizeState {

  @JsonProperty("started_at_ms")
  private final long startedAtMs;

  @JsonProperty("table_list")
  private final List<String> tableList;

  @JsonProperty("completed_tables")
  private final Set<String> completedTables;

  /**
   * The fingerprint of the storage configuration that the run started with. Null if the state was
   * created by an older version of the tool, which did not keep it.
   */
  @JsonProperty("storage_config")
  @Nullable
  private final Map<String, String> storageConfig;

  /** Creates an initial state with no completed tables. */
  public LedgerFinalizeState(
      long startedAtMs, List<String> tableList, Map<String, String> storageConfig) {
    this(startedAtMs, tableList, null, storageConfig);
  }

  @JsonCreator
  public LedgerFinalizeState(
      @JsonProperty("started_at_ms") long startedAtMs,
      @JsonProperty("table_list") List<String> tableList,
      @JsonProperty("completed_tables") List<String> completedTables,
      @JsonProperty("storage_config") @Nullable Map<String, String> storageConfig) {
    this.startedAtMs = startedAtMs;
    this.tableList = tableList != null ? new ArrayList<>(tableList) : new ArrayList<>();
    this.completedTables =
        completedTables != null ? new LinkedHashSet<>(completedTables) : new LinkedHashSet<>();
    this.storageConfig = storageConfig != null ? new TreeMap<>(storageConfig) : null;
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

  /**
   * Returns the fingerprint of the storage configuration that the run started with, or null if the
   * state was created by an older version of the tool.
   */
  @Nullable
  public Map<String, String> getStorageConfig() {
    return storageConfig != null ? Collections.unmodifiableMap(storageConfig) : null;
  }

  public void markTableCompleted(String qualifiedTableName) {
    completedTables.add(qualifiedTableName);
  }
}
