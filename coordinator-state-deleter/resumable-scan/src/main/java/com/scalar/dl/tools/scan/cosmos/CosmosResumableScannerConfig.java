package com.scalar.dl.tools.scan.cosmos;

import com.scalar.db.config.DatabaseConfig;

/** Configuration for {@link CosmosResumableScanner}, extending ScalarDB properties. */
public final class CosmosResumableScannerConfig {

  static final String PROP_MAX_SCAN_THREADS = "scalar.dl.tools.scan.max_threads";
  static final String PROP_SCAN_PAGE_SIZE = "scalar.dl.tools.scan.page_size";
  private static final int DEFAULT_MAX_WORKER_THREADS = 32;
  private static final int DEFAULT_MAX_ITEM_COUNT = 100;

  private final int maxWorkerThreads;
  private final int maxItemCount;

  public CosmosResumableScannerConfig(DatabaseConfig databaseConfig) {
    this.maxWorkerThreads =
        getIntProperty(databaseConfig, PROP_MAX_SCAN_THREADS, DEFAULT_MAX_WORKER_THREADS);
    this.maxItemCount = getIntProperty(databaseConfig, PROP_SCAN_PAGE_SIZE, DEFAULT_MAX_ITEM_COUNT);
  }

  private static int getIntProperty(DatabaseConfig config, String key, int defaultValue) {
    String value = config.getProperties().getProperty(key);
    if (value == null) {
      return defaultValue;
    }
    return Integer.parseInt(value);
  }

  public int getMaxWorkerThreads() {
    return maxWorkerThreads;
  }

  public int getMaxItemCount() {
    return maxItemCount;
  }
}
