package com.scalar.dl.tools.common;

/**
 * The errors raised by the ScalarDL cleanup tools.
 *
 * <p>Every error has a code of the form {@code DL-TOOLS-<categoryId><id>} (e.g. {@code
 * DL-TOOLS-1001}). The ids are sequential within each {@link Category}.
 */
public enum ScalarDlCleanupError implements ScalarDlToolsError {

  //
  // Errors for the user error category
  //
  COMPLETION_TOKEN_CRC_MISMATCH(
      Category.USER_ERROR,
      "001",
      "CRC32C mismatch in the completion token: expected %s but got %s.",
      "",
      "Verify that the completion token was copied verbatim and is not truncated or altered."),
  COMPLETION_TOKEN_DECODE_FAILED(
      Category.USER_ERROR,
      "002",
      "Failed to decode the completion token.",
      "",
      "Verify that the completion token was copied verbatim and is not truncated or altered."),
  UNKNOWN_SERVER_TYPE(
      Category.USER_ERROR,
      "003",
      "Unknown server type in the completion token: %s.",
      "",
      "Verify that the completion token was copied verbatim and is not truncated or altered."),
  BOTH_COMPLETION_TOKENS_REQUIRED(
      Category.USER_ERROR,
      "004",
      "Both Ledger and Auditor completion tokens are required for the initial run.",
      "",
      "Provide both the Ledger and Auditor completion tokens emitted by the finalization commands."),
  LEDGER_TOKEN_WRONG_SERVER_TYPE(
      Category.USER_ERROR,
      "005",
      "The Ledger token has the wrong server type: %s.",
      "",
      "Provide the token emitted by 'finalize-ledger' as the Ledger completion token."),
  AUDITOR_TOKEN_WRONG_SERVER_TYPE(
      Category.USER_ERROR,
      "006",
      "The Auditor token has the wrong server type: %s.",
      "",
      "Provide the token emitted by 'finalize-auditor' as the Auditor completion token."),
  AUDITOR_COMPLETION_TOKEN_REQUIRED(
      Category.USER_ERROR,
      "007",
      "The Auditor completion token is required for the initial run.",
      "",
      "Provide the Auditor completion token emitted by 'finalize-auditor'."),
  CONFIGURATION_LOAD_FAILED(
      Category.USER_ERROR,
      "008",
      "Failed to load the configuration from %s.",
      "",
      "Verify that the path points to a readable configuration file in properties format."),
  INVALID_CHECKPOINT_DIRECTORY(
      Category.USER_ERROR,
      "009",
      "The checkpoint directory path is invalid: %s.",
      "",
      "Provide a valid filesystem path for the checkpoint directory."),
  UNSUPPORTED_STORAGE(
      Category.USER_ERROR,
      "010",
      "The configured storage '%s' is not supported.",
      "",
      "Use a supported storage."),
  UNSUPPORTED_TRANSACTION_MANAGER(
      Category.USER_ERROR,
      "011",
      "The configured transaction manager '%s' is not supported.",
      "",
      "This tool supports only the ScalarDB Consensus Commit transaction manager."),
  GROUP_COMMIT_NOT_SUPPORTED(
      Category.USER_ERROR,
      "012",
      "The Coordinator group commit is not supported.",
      "",
      "This tool supports only configurations with the Coordinator group commit disabled."),
  TOO_FEW_TARGET_TABLES(
      Category.USER_ERROR,
      "013",
      "Only %d transactional table(s) were found, but the Ledger schema always has at least the"
          + " asset and asset_metadata tables.",
      "",
      "Verify that the configuration points to the database the Ledger uses and that the ScalarDL"
          + " schema has been loaded."),
  RECOVER_ASSET_LOCK_RPC_UNSUPPORTED(
      Category.USER_ERROR,
      "014",
      "The Auditor does not implement the RecoverAssetLock RPC that this command requires.",
      "",
      "Upgrade the Auditor to a version that implements RecoverAssetLock, then re-run the"
          + " command."),
  UNSUPPORTED_STORAGE_IN_MULTI_STORAGE(
      Category.USER_ERROR,
      "015",
      "The storage '%s' in the multi-storage configuration is '%s', which is not supported.",
      "",
      "Use only Cosmos DB and JDBC databases as the storages in the multi-storage configuration."),
  MULTI_STORAGE_TABLE_MAPPING_NOT_SUPPORTED(
      Category.USER_ERROR,
      "016",
      "The multi-storage table mapping (scalar.db.multi_storage.table_mapping) is not supported.",
      "",
      "Use the namespace mapping (scalar.db.multi_storage.namespace_mapping) instead."),
  INVALID_MULTI_STORAGE_CONFIGURATION(
      Category.USER_ERROR,
      "017",
      "The multi-storage configuration is invalid: %s.",
      "",
      "Check that every storage name in the scalar.db.multi_storage.* properties is listed in"
          + " scalar.db.multi_storage.storages, and that each mapping entry has the form"
          + " <namespace>:<storage>."),
  MULTI_STORAGE_DEFAULT_STORAGE_NOT_COSMOS(
      Category.USER_ERROR,
      "018",
      "The default storage '%s' in the multi-storage configuration is '%s', but it must be Cosmos"
          + " DB.",
      "",
      "Set scalar.db.multi_storage.default_storage to the storage of the Ledger's Cosmos DB"
          + " account, and map the namespaces in the other storages with"
          + " scalar.db.multi_storage.namespace_mapping."),
  MULTI_STORAGE_COORDINATOR_NAMESPACE_NOT_IN_DEFAULT_STORAGE(
      Category.USER_ERROR,
      "019",
      "The Coordinator namespace '%s' is mapped to the storage '%s', which is not the default"
          + " storage.",
      "",
      "Remove the Coordinator namespace from scalar.db.multi_storage.namespace_mapping. The"
          + " Coordinator table must be in the Ledger's Cosmos DB account, which is the default"
          + " storage."),
  MULTI_STORAGE_MAPPED_NAMESPACE_NOT_FOUND(
      Category.USER_ERROR,
      "020",
      "The namespace '%s' is mapped to the storage '%s' but was not found in the metadata of that"
          + " storage.",
      "",
      "Check scalar.db.multi_storage.namespace_mapping for a misspelled namespace, a leftover"
          + " placeholder, or a namespace mapped to the wrong storage. A namespace that has no"
          + " tables is not in the metadata either, so remove it from the mapping. For a JDBC"
          + " storage, also check that its settings, such as"
          + " scalar.db.jdbc.table_metadata.schema, match the ones of the instance that owns the"
          + " namespace."),
  MULTI_STORAGE_UNREACHABLE_NAMESPACES_FOUND(
      Category.USER_ERROR,
      "021",
      "The storage '%s' has transactional tables in the namespaces %s, but the namespace mapping"
          + " resolves those namespaces to another storage.",
      "",
      "Fix scalar.db.multi_storage.namespace_mapping so that every namespace resolves to the"
          + " storage that has its transactional tables. A namespace without an entry resolves to"
          + " the default storage. Otherwise, the records in those tables are not finalized."),
  MULTI_STORAGE_STORAGE_WITHOUT_MAPPED_NAMESPACE(
      Category.USER_ERROR,
      "022",
      "No namespace is mapped to the storage '%s' in the multi-storage configuration.",
      "",
      "Map every namespace that has tables in that storage to it with"
          + " scalar.db.multi_storage.namespace_mapping, or remove the storage from"
          + " scalar.db.multi_storage.storages if it has no tables to finalize."),

  //
  // Errors for the internal error category
  //
  STATE_LOAD_FAILED(
      Category.INTERNAL_ERROR,
      "001",
      "Failed to load the state from %s.",
      "",
      "Verify that the checkpoint directory is readable and the state file is not corrupted."),
  STATE_PERSIST_FAILED(
      Category.INTERNAL_ERROR,
      "002",
      "Failed to persist the state to %s.",
      "",
      "Verify that the checkpoint directory is writable and has sufficient free space."),
  RECOVER_ASSET_LOCK_RPC_FAILED(
      Category.INTERNAL_ERROR,
      "003",
      "The asset lock recovery failed for asset %s in namespace %s.",
      "",
      "Verify that the Auditor is healthy and reachable."),
  RECOVER_ASSET_LOCK_NOT_RECOVERABLE(
      Category.INTERNAL_ERROR,
      "004",
      "The asset lock for asset %s in namespace %s is still not recoverable after %d attempts; "
          + "the lock is being kept active.",
      "",
      "Re-run the command after the affected asset becomes quiescent.");

  private static final String COMPONENT_NAME = "DL-TOOLS";

  private final Category category;
  private final String id;
  private final String message;
  private final String cause;
  private final String solution;

  ScalarDlCleanupError(
      Category category, String id, String message, String cause, String solution) {
    validate(COMPONENT_NAME, category, id, message, cause, solution);

    this.category = category;
    this.id = id;
    this.message = message;
    this.cause = cause;
    this.solution = solution;
  }

  @Override
  public String getComponentName() {
    return COMPONENT_NAME;
  }

  @Override
  public Category getCategory() {
    return category;
  }

  @Override
  public String getId() {
    return id;
  }

  @Override
  public String getMessage() {
    return message;
  }

  @SuppressWarnings("unused")
  @Override
  public String getCause() {
    return cause;
  }

  @Override
  public String getSolution() {
    return solution;
  }
}
