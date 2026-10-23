package com.scalar.dl.tools.scan;

import com.scalar.db.api.Result;

/**
 * Callback invoked by {@link ResumableScanner} for each scanned record.
 *
 * <p>Implementations must be thread-safe, because some scanners invoke the handler concurrently
 * from multiple threads.
 *
 * <p>Implementations must be idempotent, because a record may be delivered more than once (see
 * {@link ResumableScanner}).
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
