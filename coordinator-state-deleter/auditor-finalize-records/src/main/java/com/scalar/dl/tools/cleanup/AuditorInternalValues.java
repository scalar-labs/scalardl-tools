package com.scalar.dl.tools.cleanup;

/** Internal Auditor constants: table/column names, lock-type values, and timing parameters. */
public final class AuditorInternalValues {

  public static final String TABLE_NAME = "asset_lock";
  public static final String ID = "id";
  public static final String LOCK_TYPE = "lock_type";
  public static final String LAST_UPDATED_AT = "last_updated_at";

  public static final int LOCK_TYPE_NONE = 1; // LockType.NONE
  public static final int LOCK_TYPE_READ = 2; // LockType.READ
  public static final int LOCK_TYPE_WRITE = 3; // LockType.WRITE
  public static final long LOCK_VALID_PERIOD_MS = 15_000L; // Auditor's LOCK_VALID_PERIOD_MILLIS

  // Namespace registry table constants.
  public static final String NAMESPACE_TABLE = "namespace";
  public static final String NAMESPACE_COLUMN_PARTITION_ID = "partition_id";
  public static final String NAMESPACE_COLUMN_NAME = "name";
  public static final int NAMESPACE_DEFAULT_PARTITION_ID = 0;

  // The logical namespace that resolves to the base namespace; never stored in the registry.
  public static final String DEFAULT_LOGICAL_NAMESPACE = "default";
  public static final String NAMESPACE_NAME_SEPARATOR = "_";

  // Auditor base-namespace configuration.
  public static final String AUDITOR_NAMESPACE_PROPERTY = "scalar.dl.auditor.namespace";
  public static final String DEFAULT_BASE_NAMESPACE = "auditor";

  /** Resolves a logical namespace to its physical ScalarDB namespace. */
  public static String resolveNamespace(String baseNamespace, String logicalNamespace) {
    if (DEFAULT_LOGICAL_NAMESPACE.equals(logicalNamespace)) {
      return baseNamespace;
    }
    return baseNamespace + NAMESPACE_NAME_SEPARATOR + logicalNamespace;
  }

  private AuditorInternalValues() {}
}
