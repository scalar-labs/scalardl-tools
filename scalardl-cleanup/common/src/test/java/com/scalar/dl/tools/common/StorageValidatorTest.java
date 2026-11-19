package com.scalar.dl.tools.common;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scalar.db.config.DatabaseConfig;
import com.scalar.db.storage.multistorage.MultiStorageConfig;
import com.scalar.db.transaction.consensuscommit.ConsensusCommitConfig;
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
      validateAllowingMultiStorage_unknownDefaultStorageGiven_shouldThrowScalarDlCleanupException() {
    // Arrange
    Properties props = multiStorageProperties("jdbc");
    props.setProperty(MultiStorageConfig.DEFAULT_STORAGE, "unknown_db");
    DatabaseConfig databaseConfig = new DatabaseConfig(props);

    // Act Assert
    assertThatThrownBy(() -> StorageValidator.validateAllowingMultiStorage(databaseConfig))
        .isInstanceOf(ScalarDlCleanupException.class)
        .hasMessageContaining(ScalarDlCleanupError.INVALID_MULTI_STORAGE_CONFIGURATION.buildCode())
        .hasMessageContaining("unknown_db")
        .hasCauseInstanceOf(IllegalArgumentException.class)
        .extracting(e -> ((ScalarDlCleanupException) e).getCategory())
        .isEqualTo(Category.USER_ERROR);
  }

  @Test
  void
      validateAllowingMultiStorage_namespaceMappingWithoutColonGiven_shouldThrowScalarDlCleanupException() {
    // Arrange
    Properties props = multiStorageProperties("jdbc");
    props.setProperty(MultiStorageConfig.NAMESPACE_MAPPING, "ns");
    DatabaseConfig databaseConfig = new DatabaseConfig(props);

    // Act Assert
    assertThatThrownBy(() -> StorageValidator.validateAllowingMultiStorage(databaseConfig))
        .isInstanceOf(ScalarDlCleanupException.class)
        .hasMessageContaining(ScalarDlCleanupError.INVALID_MULTI_STORAGE_CONFIGURATION.buildCode())
        .hasCauseInstanceOf(ArrayIndexOutOfBoundsException.class);
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

  @Test
  void
      validateAllowingMultiStorage_multiStorageWithJdbcDefaultStorageGiven_shouldThrowScalarDlCleanupException() {
    // Arrange
    Properties props = multiStorageProperties("jdbc");
    props.setProperty(MultiStorageConfig.NAMESPACE_MAPPING, "ns:cosmos_db");
    props.setProperty(MultiStorageConfig.DEFAULT_STORAGE, "other_db");
    DatabaseConfig databaseConfig = new DatabaseConfig(props);

    // Act Assert
    assertThatThrownBy(() -> StorageValidator.validateAllowingMultiStorage(databaseConfig))
        .isInstanceOf(ScalarDlCleanupException.class)
        .hasMessageContaining(
            ScalarDlCleanupError.MULTI_STORAGE_DEFAULT_STORAGE_NOT_COSMOS.buildCode())
        .hasMessageContaining("other_db")
        .hasMessageContaining("jdbc");
  }

  @Test
  void
      validateAllowingMultiStorage_defaultCoordinatorNamespaceMappedToNonDefaultStorageGiven_shouldThrowScalarDlCleanupException() {
    // Arrange
    Properties props = multiStorageProperties("jdbc");
    props.setProperty(MultiStorageConfig.NAMESPACE_MAPPING, "ns:other_db,coordinator:other_db");
    DatabaseConfig databaseConfig = new DatabaseConfig(props);

    // Act Assert
    assertThatThrownBy(() -> StorageValidator.validateAllowingMultiStorage(databaseConfig))
        .isInstanceOf(ScalarDlCleanupException.class)
        .hasMessageContaining(
            ScalarDlCleanupError.MULTI_STORAGE_COORDINATOR_NAMESPACE_NOT_IN_DEFAULT_STORAGE
                .buildCode())
        .hasMessageContaining("coordinator")
        .hasMessageContaining("other_db");
  }

  @Test
  void
      validateAllowingMultiStorage_customCoordinatorNamespaceMappedToNonDefaultStorageGiven_shouldThrowScalarDlCleanupException() {
    // Arrange
    Properties props = multiStorageProperties("jdbc");
    props.setProperty(ConsensusCommitConfig.COORDINATOR_NAMESPACE, "my_coordinator");
    props.setProperty(MultiStorageConfig.NAMESPACE_MAPPING, "my_coordinator:other_db");
    DatabaseConfig databaseConfig = new DatabaseConfig(props);

    // Act Assert
    assertThatThrownBy(() -> StorageValidator.validateAllowingMultiStorage(databaseConfig))
        .isInstanceOf(ScalarDlCleanupException.class)
        .hasMessageContaining(
            ScalarDlCleanupError.MULTI_STORAGE_COORDINATOR_NAMESPACE_NOT_IN_DEFAULT_STORAGE
                .buildCode())
        .hasMessageContaining("my_coordinator");
  }

  @Test
  void
      validateAllowingMultiStorage_coordinatorNamespaceMappedToDefaultStorageGiven_shouldNotThrow() {
    // Arrange
    Properties props = multiStorageProperties("jdbc");
    props.setProperty(MultiStorageConfig.NAMESPACE_MAPPING, "ns:other_db,coordinator:cosmos_db");
    DatabaseConfig databaseConfig = new DatabaseConfig(props);

    // Act Assert
    assertThatCode(() -> StorageValidator.validateAllowingMultiStorage(databaseConfig))
        .doesNotThrowAnyException();
  }

  @Test
  void
      validateAllowingMultiStorage_coordinatorNamespaceWithWhitespaceGiven_shouldThrowScalarDlCleanupException() {
    // Arrange
    // ScalarDB trims the property value, so the validator must resolve the same namespace.
    Properties props = multiStorageProperties("jdbc");
    props.setProperty(ConsensusCommitConfig.COORDINATOR_NAMESPACE, " my_coordinator ");
    props.setProperty(MultiStorageConfig.NAMESPACE_MAPPING, "ns:other_db,my_coordinator:other_db");
    DatabaseConfig databaseConfig = new DatabaseConfig(props);

    // Act Assert
    assertThatThrownBy(() -> StorageValidator.validateAllowingMultiStorage(databaseConfig))
        .isInstanceOf(ScalarDlCleanupException.class)
        .hasMessageContaining(
            ScalarDlCleanupError.MULTI_STORAGE_COORDINATOR_NAMESPACE_NOT_IN_DEFAULT_STORAGE
                .buildCode());
  }

  @Test
  void
      validateAllowingMultiStorage_storageWithoutMappedNamespaceGiven_shouldThrowScalarDlCleanupException() {
    // Arrange
    Properties props = multiStorageProperties("jdbc");
    props.setProperty(MultiStorageConfig.NAMESPACE_MAPPING, "ns:cosmos_db");
    DatabaseConfig databaseConfig = new DatabaseConfig(props);

    // Act Assert
    assertThatThrownBy(() -> StorageValidator.validateAllowingMultiStorage(databaseConfig))
        .isInstanceOf(ScalarDlCleanupException.class)
        .hasMessageContaining(
            ScalarDlCleanupError.MULTI_STORAGE_STORAGE_WITHOUT_MAPPED_NAMESPACE.buildCode())
        .hasMessageContaining("other_db");
  }

  @Test
  void
      validateAllowingMultiStorage_multiStorageWithAnotherTransactionManagerGiven_shouldNotThrow() {
    // Arrange
    // LedgerConfigValidator reports the transaction manager as a user error, so this validator
    // must not fail on it first.
    Properties props = multiStorageProperties("jdbc");
    props.setProperty(DatabaseConfig.TRANSACTION_MANAGER, "jdbc");
    DatabaseConfig databaseConfig = new DatabaseConfig(props);

    // Act Assert
    assertThatCode(() -> StorageValidator.validateAllowingMultiStorage(databaseConfig))
        .doesNotThrowAnyException();
  }
}
