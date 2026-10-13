package com.scalar.dl.tools.scan.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scalar.db.config.DatabaseConfig;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class JdbcResumableScannerConfigTest {

  private static DatabaseConfig databaseConfig(String key, String value) {
    Properties props = new Properties();
    props.setProperty(DatabaseConfig.STORAGE, "jdbc");
    props.setProperty(DatabaseConfig.CONTACT_POINTS, "jdbc:postgresql://localhost:5432/test");
    props.setProperty(DatabaseConfig.USERNAME, "user");
    props.setProperty(DatabaseConfig.PASSWORD, "password");
    if (key != null) {
      props.setProperty(key, value);
    }
    return new DatabaseConfig(props);
  }

  @Test
  void constructor_noPropertiesGiven_shouldUseDefaults() {
    // Arrange
    DatabaseConfig config = databaseConfig(null, null);

    // Act
    JdbcResumableScannerConfig scannerConfig = new JdbcResumableScannerConfig(config);

    // Assert
    assertThat(scannerConfig.getPageSize()).isEqualTo(1000);
    assertThat(scannerConfig.getMaxHandlerThreads()).isEqualTo(32);
  }

  @Test
  void constructor_pageSizeGiven_shouldUseGivenValue() {
    // Arrange
    DatabaseConfig config = databaseConfig(JdbcResumableScannerConfig.PROP_PAGE_SIZE, "250");

    // Act
    JdbcResumableScannerConfig scannerConfig = new JdbcResumableScannerConfig(config);

    // Assert
    assertThat(scannerConfig.getPageSize()).isEqualTo(250);
  }

  @Test
  void constructor_maxHandlerThreadsGiven_shouldUseGivenValue() {
    // Arrange
    DatabaseConfig config =
        databaseConfig(JdbcResumableScannerConfig.PROP_MAX_HANDLER_THREADS, "8");

    // Act
    JdbcResumableScannerConfig scannerConfig = new JdbcResumableScannerConfig(config);

    // Assert
    assertThat(scannerConfig.getMaxHandlerThreads()).isEqualTo(8);
  }

  @Test
  void constructor_nonNumericPageSizeGiven_shouldThrowIllegalArgumentException() {
    // Arrange
    DatabaseConfig config =
        databaseConfig(JdbcResumableScannerConfig.PROP_PAGE_SIZE, "not-a-number");

    // Act & Assert
    assertThatThrownBy(() -> new JdbcResumableScannerConfig(config))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(JdbcResumableScannerConfig.PROP_PAGE_SIZE);
  }

  @Test
  void constructor_zeroPageSizeGiven_shouldThrowIllegalArgumentException() {
    // Arrange
    DatabaseConfig config = databaseConfig(JdbcResumableScannerConfig.PROP_PAGE_SIZE, "0");

    // Act & Assert
    assertThatThrownBy(() -> new JdbcResumableScannerConfig(config))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("positive");
  }

  @Test
  void constructor_negativeMaxHandlerThreadsGiven_shouldThrowIllegalArgumentException() {
    // Arrange
    DatabaseConfig config =
        databaseConfig(JdbcResumableScannerConfig.PROP_MAX_HANDLER_THREADS, "-1");

    // Act & Assert
    assertThatThrownBy(() -> new JdbcResumableScannerConfig(config))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("positive");
  }
}
