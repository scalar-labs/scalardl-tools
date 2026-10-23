package com.scalar.dl.tools.scan.jdbc;

import static com.scalar.dl.tools.scan.ScannerConfigUtils.getPositiveIntProperty;

import com.scalar.db.config.DatabaseConfig;

/** Configuration for {@link JdbcResumableScanner}, extending ScalarDB properties. */
public final class JdbcResumableScannerConfig {

  static final String PROP_SCAN_PAGE_SIZE = "scalar.dl.tools.scan.jdbc.page_size";
  private static final int DEFAULT_PAGE_SIZE = 100;

  private final int pageSize;

  public JdbcResumableScannerConfig(DatabaseConfig databaseConfig) {
    this.pageSize = getPositiveIntProperty(databaseConfig, PROP_SCAN_PAGE_SIZE, DEFAULT_PAGE_SIZE);
  }

  public int getPageSize() {
    return pageSize;
  }
}
