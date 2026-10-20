package com.scalar.dl.tools.scan;

import com.scalar.db.config.DatabaseConfig;

/** Utilities for reading the scanner-specific properties from ScalarDB properties. */
public final class ScannerConfigUtils {

  private ScannerConfigUtils() {}

  /**
   * Returns the value of the given property as a positive integer, or the default value if the
   * property is not set.
   *
   * @throws IllegalArgumentException if the value is not a valid integer or not positive
   */
  public static int getPositiveIntProperty(DatabaseConfig config, String key, int defaultValue) {
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
}
