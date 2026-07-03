package com.scalar.dl.tools.common;

import com.scalar.db.config.DatabaseConfig;
import com.scalar.db.transaction.consensuscommit.ConsensusCommitConfig;

/**
 * Validates that the ScalarDL configuration (including ScalarDB configuration) is supported by the
 * tool.
 */
public final class LedgerConfigValidator {

  private static final String JDBC_TRANSACTION_MANAGER = "jdbc";

  private LedgerConfigValidator() {}

  /**
   * Throws if the configuration is not supported by the Ledger-side tools.
   *
   * @param databaseConfig the ScalarDB database configuration
   * @throws CoordinatorStateDeleterException if the JDBC transaction manager is used, or if the
   *     Coordinator group commit is enabled
   */
  public static void validate(DatabaseConfig databaseConfig) {
    throwIfJdbcTransactionManager(databaseConfig);
    throwIfGroupCommitEnabled(databaseConfig);
  }

  private static void throwIfJdbcTransactionManager(DatabaseConfig databaseConfig) {
    if (JDBC_TRANSACTION_MANAGER.equalsIgnoreCase(databaseConfig.getTransactionManager())) {
      throw new CoordinatorStateDeleterException(
          CoordinatorStateDeleterError.JDBC_TRANSACTION_MANAGER_NOT_SUPPORTED);
    }
  }

  private static void throwIfGroupCommitEnabled(DatabaseConfig databaseConfig) {
    if (new ConsensusCommitConfig(databaseConfig).isCoordinatorGroupCommitEnabled()) {
      throw new CoordinatorStateDeleterException(
          CoordinatorStateDeleterError.GROUP_COMMIT_NOT_SUPPORTED);
    }
  }
}
