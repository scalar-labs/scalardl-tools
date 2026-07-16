package com.scalar.dl.tools.common;

import com.scalar.db.config.DatabaseConfig;
import com.scalar.db.transaction.consensuscommit.ConsensusCommitConfig;
import java.util.Objects;

/**
 * Validates that the ScalarDL configuration (including ScalarDB configuration) is supported by the
 * tool.
 */
public final class LedgerConfigValidator {

  private LedgerConfigValidator() {}

  /**
   * Throws if the configuration is not supported by the Ledger-side tools.
   *
   * @param databaseConfig the ScalarDB database configuration
   * @throws ScalarDlCleanupException if the transaction manager is not the Consensus Commit
   *     transaction manager, or if the Coordinator group commit is enabled
   */
  public static void validate(DatabaseConfig databaseConfig) {
    Objects.requireNonNull(databaseConfig, "databaseConfig must not be null");
    throwIfNotConsensusCommitTransactionManager(databaseConfig);
    throwIfGroupCommitEnabled(databaseConfig);
  }

  private static void throwIfNotConsensusCommitTransactionManager(DatabaseConfig databaseConfig) {
    String transactionManager = databaseConfig.getTransactionManager();
    if (!ConsensusCommitConfig.TRANSACTION_MANAGER_NAME.equals(transactionManager)) {
      throw new ScalarDlCleanupException(
          ScalarDlCleanupError.UNSUPPORTED_TRANSACTION_MANAGER, transactionManager);
    }
  }

  private static void throwIfGroupCommitEnabled(DatabaseConfig databaseConfig) {
    if (new ConsensusCommitConfig(databaseConfig).isCoordinatorGroupCommitEnabled()) {
      throw new ScalarDlCleanupException(ScalarDlCleanupError.GROUP_COMMIT_NOT_SUPPORTED);
    }
  }
}
