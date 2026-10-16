package com.scalar.dl.tools.scan.jdbc;

import com.scalar.db.config.DatabaseConfig;

/** Configuration for {@link JdbcResumableScanner}, extending ScalarDB properties. */
public final class JdbcResumableScannerConfig {

  static final String PROP_SCAN_PAGE_SIZE = "scalar.dl.tools.scan.jdbc.page_size";
  private static final int DEFAULT_PAGE_SIZE = 100;

  private final int pageSize;

  public JdbcResumableScannerConfig(DatabaseConfig databaseConfig) {
    this.pageSize = getPositiveIntProperty(databaseConfig, PROP_SCAN_PAGE_SIZE, DEFAULT_PAGE_SIZE);
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

  public int getPageSize() {
    return pageSize;
  }
}
