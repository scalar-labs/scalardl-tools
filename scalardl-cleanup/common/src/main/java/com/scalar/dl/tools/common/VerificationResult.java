package com.scalar.dl.tools.common;

/** The verdict of a verify command, which checks that a finalize command left nothing behind. */
public enum VerificationResult {
  /** Every target was scanned to the end and nothing that still needs finalization was found. */
  PASSED,
  /** Something that still needs finalization was found. The scan stopped at the first one. */
  FAILED
}
