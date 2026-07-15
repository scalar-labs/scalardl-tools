package com.scalar.dl.tools.scan;

import com.scalar.db.api.Result;

/**
 * Callback invoked by {@link ResumableScanner} for each scanned record.
 *
 * <p>Implementations must be thread-safe: the scanner invokes the handler concurrently from
 * multiple scan worker threads (one per physical partition).
 */
@FunctionalInterface
public interface RecordHandler {

  /**
   * Handles a single scanned record.
   *
   * @param record the scanned record
   * @throws Exception if handling fails; the scan is aborted and the exception is propagated
   */
  void handle(Result record) throws Exception;
}
