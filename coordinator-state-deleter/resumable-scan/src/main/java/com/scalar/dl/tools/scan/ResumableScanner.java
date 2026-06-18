package com.scalar.dl.tools.scan;

/**
 * A resumable, parallel scanner over a ScalarDB table.
 *
 * <p>Callers specify a ScalarDB namespace and table name; the scanner delivers each record to the
 * provided {@link RecordHandler}. Progress is checkpointed to enable resume-after-failure.
 */
public interface ResumableScanner extends AutoCloseable {

  /**
   * Start or resume a parallel scan over the given ScalarDB table.
   *
   * @param namespace ScalarDB namespace of the table to scan
   * @param tableName ScalarDB table name to scan
   * @param recordHandler callback invoked for each scanned record; must be thread-safe
   * @return the scan result
   */
  ScanResult scan(String namespace, String tableName, RecordHandler recordHandler);
}
