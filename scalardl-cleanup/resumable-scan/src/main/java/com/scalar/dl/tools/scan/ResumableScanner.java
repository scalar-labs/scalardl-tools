package com.scalar.dl.tools.scan;

/**
 * A resumable scanner over a ScalarDB table.
 *
 * <p>Callers specify a ScalarDB namespace and table name; the scanner delivers each record to the
 * provided {@link RecordHandler}. Progress is checkpointed to enable resume-after-failure.
 *
 * <p>Records whose handling did not complete before an interruption may be delivered again on
 * resume (at-least-once), so the handler must be idempotent.
 */
public interface ResumableScanner extends AutoCloseable {

  /**
   * Start or resume a scan over the given ScalarDB table.
   *
   * @param namespace ScalarDB namespace of the table to scan
   * @param tableName ScalarDB table name to scan
   * @param recordHandler callback invoked for each scanned record; must be thread-safe and
   *     idempotent
   * @return the scan result
   * @throws Exception if the scan fails; an exception thrown by {@code recordHandler} propagates
   *     as-is
   */
  ScanResult scan(String namespace, String tableName, RecordHandler recordHandler) throws Exception;
}
