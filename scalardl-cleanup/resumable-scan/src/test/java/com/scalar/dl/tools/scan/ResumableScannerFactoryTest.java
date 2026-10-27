package com.scalar.dl.tools.scan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scalar.db.config.DatabaseConfig;
import com.scalar.db.storage.multistorage.MultiStorageConfig;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResumableScannerFactoryTest {

  private static final String PAGE_SIZE_PROPERTY = "scalar.dl.tools.scan.jdbc.page_size";

  @TempDir Path tempDir;

  private static Properties multiStorageProperties() {
    Properties props = new Properties();
    props.setProperty(DatabaseConfig.STORAGE, MultiStorageConfig.STORAGE_NAME);
    props.setProperty(MultiStorageConfig.STORAGES, "cosmos_db,postgres");
    props.setProperty(MultiStorageConfig.STORAGES + ".cosmos_db.storage", "cosmos");
    props.setProperty(
        MultiStorageConfig.STORAGES + ".cosmos_db.contact_points", "https://localhost:8081");
    props.setProperty(MultiStorageConfig.STORAGES + ".postgres.storage", "jdbc");
    props.setProperty(
        MultiStorageConfig.STORAGES + ".postgres.contact_points",
        "jdbc:postgresql://localhost:5432/postgres");
    props.setProperty(MultiStorageConfig.NAMESPACE_MAPPING, "coordinator:postgres");
    props.setProperty(MultiStorageConfig.DEFAULT_STORAGE, "cosmos_db");
    props.setProperty(PAGE_SIZE_PROPERTY, "50");
    return props;
  }

  @Test
  void resolveDatabaseConfig_singleStorageGiven_shouldReturnConfiguredConfig() {
    // Arrange
    Properties props = new Properties();
    props.setProperty(DatabaseConfig.STORAGE, "jdbc");
    DatabaseConfig databaseConfig = new DatabaseConfig(props);
    ResumableScannerFactory factory = new ResumableScannerFactory(databaseConfig);

    // Act
    DatabaseConfig resolved = factory.resolveDatabaseConfig("ns");

    // Assert
    assertThat(resolved).isSameAs(databaseConfig);
  }

  @Test
  void resolveDatabaseConfig_namespaceInNamespaceMappingGiven_shouldResolveMappedStorage() {
    // Arrange
    ResumableScannerFactory factory =
        new ResumableScannerFactory(new DatabaseConfig(multiStorageProperties()));

    // Act
    DatabaseConfig resolved = factory.resolveDatabaseConfig("coordinator");

    // Assert
    assertThat(resolved.getStorage()).isEqualTo("jdbc");
    assertThat(resolved.getContactPoints())
        .containsExactly("jdbc:postgresql://localhost:5432/postgres");
  }

  @Test
  void resolveDatabaseConfig_namespaceNotInMappingGiven_shouldResolveDefaultStorage() {
    // Arrange
    ResumableScannerFactory factory =
        new ResumableScannerFactory(new DatabaseConfig(multiStorageProperties()));

    // Act
    DatabaseConfig resolved = factory.resolveDatabaseConfig("ns");

    // Assert
    assertThat(resolved.getStorage()).isEqualTo("cosmos");
    assertThat(resolved.getContactPoints()).containsExactly("https://localhost:8081");
  }

  @Test
  void resolveDatabaseConfig_multiStorageGiven_shouldKeepToolProperties() {
    // Arrange
    ResumableScannerFactory factory =
        new ResumableScannerFactory(new DatabaseConfig(multiStorageProperties()));

    // Act
    DatabaseConfig resolved = factory.resolveDatabaseConfig("coordinator");

    // Assert
    assertThat(resolved.getProperties().getProperty(PAGE_SIZE_PROPERTY)).isEqualTo("50");
  }

  @Test
  void create_unsupportedStorageGiven_shouldThrowIllegalArgumentException() {
    // Arrange
    Properties props = new Properties();
    props.setProperty(DatabaseConfig.STORAGE, "cassandra");
    ResumableScannerFactory factory = new ResumableScannerFactory(new DatabaseConfig(props));

    // Act Assert
    assertThatThrownBy(() -> factory.create("ns", tempDir))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cassandra");
  }
}
