package com.scalar.dl.tools.scan;

import javax.annotation.concurrent.Immutable;

/** Result of a completed scan. */
@Immutable
public class ScanResult {

  private final long totalScanned;

  public ScanResult(long totalScanned) {
    this.totalScanned = totalScanned;
  }

  /** Total number of records scanned across all partitions. */
  public long getTotalScanned() {
    return totalScanned;
  }
}
