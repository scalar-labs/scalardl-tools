package com.scalar.dl.tools.common;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scalar.db.config.DatabaseConfig;
import com.scalar.db.transaction.consensuscommit.ConsensusCommitConfig;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class LedgerConfigValidatorTest {

  private static Properties baseProperties() {
    Properties props = new Properties();
    props.setProperty(DatabaseConfig.STORAGE, "cosmos");
    return props;
  }

  @Test
  void validate_consensusCommitWithoutGroupCommitGiven_shouldNotThrow() {
    // Arrange
    DatabaseConfig databaseConfig = new DatabaseConfig(baseProperties());

    // Act Assert
    assertThatCode(() -> LedgerConfigValidator.validate(databaseConfig)).doesNotThrowAnyException();
  }

  @Test
  void validate_jdbcTransactionManagerGiven_shouldThrowCoordinatorStateDeleterException() {
    // Arrange
    Properties props = baseProperties();
    props.setProperty(DatabaseConfig.TRANSACTION_MANAGER, "jdbc");
    DatabaseConfig databaseConfig = new DatabaseConfig(props);

    // Act Assert
    assertThatThrownBy(() -> LedgerConfigValidator.validate(databaseConfig))
        .isInstanceOf(CoordinatorStateDeleterException.class)
        .hasMessageContaining("JDBC transaction manager")
        .hasMessageContaining("not supported");
  }

  @Test
  void validate_groupCommitEnabledGiven_shouldThrowCoordinatorStateDeleterException() {
    // Arrange
    Properties props = baseProperties();
    props.setProperty(ConsensusCommitConfig.COORDINATOR_GROUP_COMMIT_ENABLED, "true");
    DatabaseConfig databaseConfig = new DatabaseConfig(props);

    // Act Assert
    assertThatThrownBy(() -> LedgerConfigValidator.validate(databaseConfig))
        .isInstanceOf(CoordinatorStateDeleterException.class)
        .hasMessageContaining("group commit")
        .hasMessageContaining("not supported");
  }
}
