package com.scalar.dl.tools.cleanup;

import static org.assertj.core.api.Assertions.assertThat;

import com.scalar.db.config.DatabaseConfig;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class StorageIdsTest {

  private static DatabaseConfig databaseConfig(String storage, String contactPoints) {
    Properties props = new Properties();
    props.setProperty(DatabaseConfig.STORAGE, storage);
    props.setProperty(DatabaseConfig.CONTACT_POINTS, contactPoints);
    return new DatabaseConfig(props);
  }

  private static DatabaseConfig databaseConfig(
      String storage, String contactPoints, String username) {
    Properties props = new Properties();
    props.setProperty(DatabaseConfig.STORAGE, storage);
    props.setProperty(DatabaseConfig.CONTACT_POINTS, contactPoints);
    props.setProperty(DatabaseConfig.USERNAME, username);
    return new DatabaseConfig(props);
  }

  @Test
  void of_sameConfigurationGiven_shouldReturnSameId() {
    // Arrange
    DatabaseConfig config1 = databaseConfig("jdbc", "jdbc:postgresql://localhost:5432/postgres");
    DatabaseConfig config2 = databaseConfig("jdbc", "jdbc:postgresql://localhost:5432/postgres");

    // Act Assert
    assertThat(StorageIds.of(config1)).isEqualTo(StorageIds.of(config2));
  }

  @Test
  void of_differentContactPointsGiven_shouldReturnDifferentIds() {
    // Arrange
    DatabaseConfig config1 = databaseConfig("jdbc", "jdbc:postgresql://localhost:5432/db1");
    DatabaseConfig config2 = databaseConfig("jdbc", "jdbc:postgresql://localhost:5432/db2");

    // Act Assert
    assertThat(StorageIds.of(config1)).isNotEqualTo(StorageIds.of(config2));
  }

  @Test
  void of_differentUsernamesGiven_shouldReturnDifferentIds() {
    // Arrange
    // Without a database name in the URL, PostgreSQL connects to the database of the username.
    DatabaseConfig config1 = databaseConfig("jdbc", "jdbc:postgresql://localhost:5432/", "user1");
    DatabaseConfig config2 = databaseConfig("jdbc", "jdbc:postgresql://localhost:5432/", "user2");

    // Act Assert
    assertThat(StorageIds.of(config1)).isNotEqualTo(StorageIds.of(config2));
  }

  @Test
  void of_differentStorageTypesGiven_shouldReturnDifferentIds() {
    // Arrange
    DatabaseConfig config1 = databaseConfig("cosmos", "localhost");
    DatabaseConfig config2 = databaseConfig("jdbc", "localhost");

    // Act Assert
    assertThat(StorageIds.of(config1)).isNotEqualTo(StorageIds.of(config2));
  }

  @Test
  void of_shouldReturnSixteenHexCharacters() {
    // Arrange
    DatabaseConfig config = databaseConfig("cosmos", "https://localhost:8081");

    // Act
    String id = StorageIds.of(config);

    // Assert
    assertThat(id).matches("[0-9a-f]{16}");
  }
}
