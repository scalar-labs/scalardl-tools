package com.scalar.dl.tools.common;

/**
 * Internal Auditor constants: table/column names, lock-type values, and timing parameters.
 *
 * <p>These values mirror the Auditor's own definitions in the scalardl-enterprise source. This
 * module intentionally does not depend on scalardl-enterprise, so the literals are duplicated here
 * and must be kept in sync if the Auditor side changes them.
 */
public final class AuditorInternalValues {

  // asset_lock table name and columns.
  public static final String ASSET_LOCK_TABLE_NAME = "asset_lock";
  public static final String ASSET_LOCK_TABLE_ID_COLUMN_NAME = "id";
  public static final String ASSET_LOCK_TABLE_LOCK_TYPE_COLUMN_NAME = "lock_type";
  public static final String ASSET_LOCK_TABLE_LAST_UPDATED_AT_COLUMN_NAME = "last_updated_at";

  public static final int LOCK_TYPE_NONE = 1; // LockType.NONE
  public static final int LOCK_TYPE_READ = 2; // LockType.READ
  public static final int LOCK_TYPE_WRITE = 3; // LockType.WRITE
  public static final long LOCK_VALID_PERIOD_MS = 15_000L; // Auditor's LOCK_VALID_PERIOD_MILLIS

  // request_proof table name and columns.
  public static final String REQUEST_PROOF_TABLE_NAME = "request_proof";
  public static final String REQUEST_PROOF_TABLE_NONCE_COLUMN_NAME = "nonce";
  public static final String REQUEST_PROOF_TABLE_REGISTERED_AT_COLUMN_NAME = "registered_at";

  // Namespace registry table name and columns.
  public static final String NAMESPACE_TABLE_NAME = "namespace";
  public static final String NAMESPACE_TABLE_PARTITION_ID_COLUMN_NAME = "partition_id";
  public static final String NAMESPACE_TABLE_NAME_COLUMN_NAME = "name";
  public static final int NAMESPACE_TABLE_DEFAULT_PARTITION_ID = 0;

  // The logical namespace that resolves to the base namespace; never stored in the registry.
  public static final String DEFAULT_LOGICAL_NAMESPACE = "default";
  public static final String NAMESPACE_NAME_SEPARATOR = "_";

  // Auditor base-namespace configuration.
  // Keep in sync with AuditorConfig.NAMESPACE / AuditorConfig.DEFAULT_NAMESPACE.
  public static final String AUDITOR_NAMESPACE_PROPERTY = "scalar.dl.auditor.namespace";
  public static final String DEFAULT_BASE_NAMESPACE = "auditor";

  private AuditorInternalValues() {}

  /** Resolves a logical namespace to its physical ScalarDB namespace. */
  public static String resolveNamespace(String baseNamespace, String logicalNamespace) {
    if (DEFAULT_LOGICAL_NAMESPACE.equals(logicalNamespace)) {
      return baseNamespace;
    }
    return baseNamespace + NAMESPACE_NAME_SEPARATOR + logicalNamespace;
  }
}
