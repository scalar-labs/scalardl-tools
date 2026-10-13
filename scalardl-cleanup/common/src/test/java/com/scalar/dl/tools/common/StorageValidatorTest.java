package com.scalar.dl.tools.common;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scalar.db.config.DatabaseConfig;
import java.util.Properties;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StorageValidatorTest {

  private static DatabaseConfig databaseConfigWithStorage(String storage) {
    Properties props = new Properties();
    props.setProperty(DatabaseConfig.STORAGE, storage);
    return new DatabaseConfig(props);
  }

  @ParameterizedTest
  @ValueSource(strings = {"cosmos", "jdbc"})
  void validate_supportedStorageGiven_shouldNotThrow(String storage) {
    // Arrange
    DatabaseConfig databaseConfig = databaseConfigWithStorage(storage);

    // Act Assert
    assertThatCode(() -> StorageValidator.validate(databaseConfig)).doesNotThrowAnyException();
  }

  @ParameterizedTest
  @ValueSource(strings = {"cassandra", "dynamo", "multi-storage"})
  void validate_unsupportedStorageGiven_shouldThrowScalarDlCleanupException(String storage) {
    // Arrange
    DatabaseConfig databaseConfig = databaseConfigWithStorage(storage);

    // Act Assert
    assertThatThrownBy(() -> StorageValidator.validate(databaseConfig))
        .isInstanceOf(ScalarDlCleanupException.class)
        .hasMessageContaining(storage)
        .hasMessageContaining("not supported");
  }
}
