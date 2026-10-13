package com.scalar.dl.tools.scan.jdbc;

import com.scalar.db.config.DatabaseConfig;

/** Configuration for the JDBC resumable scanner, extending ScalarDB properties. */
public final class JdbcResumableScannerConfig {

  static final String PROP_PAGE_SIZE = "scalar.dl.tools.scan.jdbc.page_size";
  static final String PROP_MAX_HANDLER_THREADS = "scalar.dl.tools.scan.jdbc.max_handler_threads";
  private static final int DEFAULT_PAGE_SIZE = 1000;
  private static final int DEFAULT_MAX_HANDLER_THREADS = 32;

  private final int pageSize;
  private final int maxHandlerThreads;

  public JdbcResumableScannerConfig(DatabaseConfig databaseConfig) {
    this.pageSize = getPositiveIntProperty(databaseConfig, PROP_PAGE_SIZE, DEFAULT_PAGE_SIZE);
    this.maxHandlerThreads =
        getPositiveIntProperty(
            databaseConfig, PROP_MAX_HANDLER_THREADS, DEFAULT_MAX_HANDLER_THREADS);
  }

  private static int getPositiveIntProperty(DatabaseConfig config, String key, int defaultValue) {
    String value = config.getProperties().getProperty(key);
    if (value == null) {
      return defaultValue;
    }
    int parsed;
    try {
      parsed = Integer.parseInt(value);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(
          "The property '" + key + "' must be a valid integer: " + value, e);
    }
    if (parsed <= 0) {
      throw new IllegalArgumentException(
          "The property '" + key + "' must be a positive integer: " + value);
    }
    return parsed;
  }

  /** Returns the number of records requested per scan page. */
  public int getPageSize() {
    return pageSize;
  }

  /** Returns the maximum number of threads used to deliver records to the record handler. */
  public int getMaxHandlerThreads() {
    return maxHandlerThreads;
  }
}
