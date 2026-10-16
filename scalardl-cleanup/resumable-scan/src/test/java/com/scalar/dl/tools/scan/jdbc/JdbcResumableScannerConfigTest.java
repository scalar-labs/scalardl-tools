package com.scalar.dl.tools.scan.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scalar.db.config.DatabaseConfig;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class JdbcResumableScannerConfigTest {

  private static DatabaseConfig databaseConfig(Properties extra) {
    Properties props = new Properties();
    props.setProperty(DatabaseConfig.STORAGE, "jdbc");
    props.setProperty(DatabaseConfig.CONTACT_POINTS, "jdbc:postgresql://localhost:5432/");
    props.putAll(extra);
    return new DatabaseConfig(props);
  }

  @Test
  void getPageSize_whenPropertyNotSet_shouldReturnDefault() {
    JdbcResumableScannerConfig config =
        new JdbcResumableScannerConfig(databaseConfig(new Properties()));

    assertThat(config.getPageSize()).isEqualTo(100);
  }

  @Test
  void getPageSize_propertyGiven_shouldReturnConfiguredValue() {
    Properties props = new Properties();
    props.setProperty(JdbcResumableScannerConfig.PROP_SCAN_PAGE_SIZE, "50");

    JdbcResumableScannerConfig config = new JdbcResumableScannerConfig(databaseConfig(props));

    assertThat(config.getPageSize()).isEqualTo(50);
  }

  @Test
  void constructor_nonIntegerPageSizeGiven_shouldThrowIllegalArgumentException() {
    Properties props = new Properties();
    props.setProperty(JdbcResumableScannerConfig.PROP_SCAN_PAGE_SIZE, "abc");

    assertThatThrownBy(() -> new JdbcResumableScannerConfig(databaseConfig(props)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void constructor_nonPositivePageSizeGiven_shouldThrowIllegalArgumentException() {
    Properties props = new Properties();
    props.setProperty(JdbcResumableScannerConfig.PROP_SCAN_PAGE_SIZE, "0");

    assertThatThrownBy(() -> new JdbcResumableScannerConfig(databaseConfig(props)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
