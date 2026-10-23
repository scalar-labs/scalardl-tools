package com.scalar.dl.tools.scan.cosmos;

import static com.scalar.dl.tools.scan.ScannerConfigUtils.getPositiveIntProperty;

import com.scalar.db.config.DatabaseConfig;

/** Configuration for {@link CosmosResumableScanner}, extending ScalarDB properties. */
public final class CosmosResumableScannerConfig {

  static final String PROP_MAX_SCAN_THREADS = "scalar.dl.tools.scan.cosmos.max_threads";
  static final String PROP_SCAN_PAGE_SIZE = "scalar.dl.tools.scan.cosmos.page_size";
  private static final int DEFAULT_MAX_WORKER_THREADS = 32;
  private static final int DEFAULT_MAX_ITEM_COUNT = 100;

  private final int maxWorkerThreads;
  private final int maxItemCount;

  public CosmosResumableScannerConfig(DatabaseConfig databaseConfig) {
    this.maxWorkerThreads =
        getPositiveIntProperty(databaseConfig, PROP_MAX_SCAN_THREADS, DEFAULT_MAX_WORKER_THREADS);
    this.maxItemCount =
        getPositiveIntProperty(databaseConfig, PROP_SCAN_PAGE_SIZE, DEFAULT_MAX_ITEM_COUNT);
  }

  public int getMaxWorkerThreads() {
    return maxWorkerThreads;
  }

  public int getMaxItemCount() {
    return maxItemCount;
  }
}
