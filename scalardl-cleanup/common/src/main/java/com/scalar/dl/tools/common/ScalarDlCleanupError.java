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
      "Verify that the Auditor is healthy and reachable.");

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

  @SuppressWarnings("unused")
  @Override
  public String getSolution() {
    return solution;
  }
}
