package com.scalar.dl.tools.common;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scalar.db.config.DatabaseConfig;
import com.scalar.db.storage.multistorage.MultiStorageConfig;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StorageValidatorTest {

  private static DatabaseConfig databaseConfigWithStorage(String storage) {
    Properties props = new Properties();
    props.setProperty(DatabaseConfig.STORAGE, storage);
    return new DatabaseConfig(props);
  }

  /**
   * Returns multi-storage properties with two storages: {@code cosmos_db} of Cosmos DB, which is
   * the default storage, and {@code other_db} of the given storage, to which {@code ns} is mapped.
   */
  private static Properties multiStorageProperties(String otherStorage) {
    Properties props = new Properties();
    props.setProperty(DatabaseConfig.STORAGE, MultiStorageConfig.STORAGE_NAME);
    props.setProperty(MultiStorageConfig.STORAGES, "cosmos_db,other_db");
    props.setProperty(MultiStorageConfig.STORAGES + ".cosmos_db.storage", "cosmos");
    props.setProperty(MultiStorageConfig.STORAGES + ".other_db.storage", otherStorage);
    props.setProperty(MultiStorageConfig.NAMESPACE_MAPPING, "ns:other_db");
    props.setProperty(MultiStorageConfig.DEFAULT_STORAGE, "cosmos_db");
    return props;
  }

  @Test
  void validate_cosmosStorageGiven_shouldNotThrow() {
    // Arrange
    DatabaseConfig databaseConfig = databaseConfigWithStorage("cosmos");

    // Act Assert
    assertThatCode(() -> StorageValidator.validate(databaseConfig)).doesNotThrowAnyException();
  }

  @ParameterizedTest
  @ValueSource(strings = {"cassandra", "jdbc", "dynamo", "multi-storage"})
  void validate_nonCosmosStorageGiven_shouldThrowScalarDlCleanupException(String storage) {
    // Arrange
    DatabaseConfig databaseConfig = databaseConfigWithStorage(storage);

    // Act Assert
    assertThatThrownBy(() -> StorageValidator.validate(databaseConfig))
        .isInstanceOf(ScalarDlCleanupException.class)
        .hasMessageContaining(storage)
        .hasMessageContaining("not supported");
  }

  @Test
  void validateAllowingMultiStorage_cosmosStorageGiven_shouldNotThrow() {
    // Arrange
    DatabaseConfig databaseConfig = databaseConfigWithStorage("cosmos");

    // Act Assert
    assertThatCode(() -> StorageValidator.validateAllowingMultiStorage(databaseConfig))
        .doesNotThrowAnyException();
  }

  @ParameterizedTest
  @ValueSource(strings = {"cassandra", "jdbc", "dynamo"})
  void
      validateAllowingMultiStorage_unsupportedSingleStorageGiven_shouldThrowScalarDlCleanupException(
          String storage) {
    // Arrange
    DatabaseConfig databaseConfig = databaseConfigWithStorage(storage);

    // Act Assert
    assertThatThrownBy(() -> StorageValidator.validateAllowingMultiStorage(databaseConfig))
        .isInstanceOf(ScalarDlCleanupException.class)
        .hasMessageContaining(ScalarDlCleanupError.UNSUPPORTED_STORAGE.buildCode())
        .hasMessageContaining(storage);
  }

  @ParameterizedTest
  @ValueSource(strings = {"cosmos", "jdbc"})
  void validateAllowingMultiStorage_multiStorageWithSupportedStoragesGiven_shouldNotThrow(
      String otherStorage) {
    // Arrange
    DatabaseConfig databaseConfig = new DatabaseConfig(multiStorageProperties(otherStorage));

    // Act Assert
    assertThatCode(() -> StorageValidator.validateAllowingMultiStorage(databaseConfig))
        .doesNotThrowAnyException();
  }

  @ParameterizedTest
  @ValueSource(strings = {"cassandra", "dynamo"})
  void
      validateAllowingMultiStorage_multiStorageWithUnsupportedStorageGiven_shouldThrowScalarDlCleanupException(
          String otherStorage) {
    // Arrange
    DatabaseConfig databaseConfig = new DatabaseConfig(multiStorageProperties(otherStorage));

    // Act Assert
    assertThatThrownBy(() -> StorageValidator.validateAllowingMultiStorage(databaseConfig))
        .isInstanceOf(ScalarDlCleanupException.class)
        .hasMessageContaining(ScalarDlCleanupError.UNSUPPORTED_STORAGE_IN_MULTI_STORAGE.buildCode())
        .hasMessageContaining("other_db")
        .hasMessageContaining(otherStorage);
  }

  @Test
  void
      validateAllowingMultiStorage_multiStorageWithTableMappingGiven_shouldThrowScalarDlCleanupException() {
    // Arrange
    Properties props = multiStorageProperties("jdbc");
    props.setProperty(MultiStorageConfig.TABLE_MAPPING, "ns2.tbl:other_db");
    DatabaseConfig databaseConfig = new DatabaseConfig(props);

    // Act Assert
    assertThatThrownBy(() -> StorageValidator.validateAllowingMultiStorage(databaseConfig))
        .isInstanceOf(ScalarDlCleanupException.class)
        .hasMessageContaining(
            ScalarDlCleanupError.MULTI_STORAGE_TABLE_MAPPING_NOT_SUPPORTED.buildCode());
  }
}
