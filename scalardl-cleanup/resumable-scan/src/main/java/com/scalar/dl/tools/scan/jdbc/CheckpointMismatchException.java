package com.scalar.dl.tools.scan.jdbc;

/**
 * Thrown when a persisted keyset checkpoint cannot be applied to the table it is being restored
 * for: an unknown format version, malformed content, or primary-key columns that no longer match
 * the table metadata.
 *
 * <p>The scan must not silently continue in this case. Resuming with a keyset that does not match
 * the current table would skip records and report a successful, but incomplete, scan.
 */
public class CheckpointMismatchException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public CheckpointMismatchException(String message) {
    super(message);
  }

  public CheckpointMismatchException(String message, Throwable cause) {
    super(message, cause);
  }
}
