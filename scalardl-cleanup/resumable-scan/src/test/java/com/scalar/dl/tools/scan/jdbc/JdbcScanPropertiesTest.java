package com.scalar.dl.tools.scan.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import com.scalar.db.config.DatabaseConfig;
import com.scalar.db.storage.jdbc.JdbcConfig;
import java.lang.reflect.Method;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/** Verifies the properties the scanner derives for its own storage instance. */
class JdbcScanPropertiesTest {

  private static Properties buildScanProperties(Properties original) throws Exception {
    Method method =
        JdbcResumableScanner.class.getDeclaredMethod("buildScanProperties", Properties.class);
    method.setAccessible(true);
    return (Properties) method.invoke(null, original);
  }

  private static Properties baseProperties() {
    Properties properties = new Properties();
    properties.setProperty(DatabaseConfig.STORAGE, "jdbc");
    properties.setProperty(DatabaseConfig.CONTACT_POINTS, "jdbc:postgresql://localhost:5432/db");
    properties.setProperty(DatabaseConfig.USERNAME, "user");
    return properties;
  }

  @Test
  void buildScanProperties_defaultsGiven_shouldEnableCrossPartitionScanOptions() throws Exception {
    // Act
    Properties scanProperties = buildScanProperties(baseProperties());

    // Assert
    assertThat(scanProperties.getProperty(DatabaseConfig.CROSS_PARTITION_SCAN)).isEqualTo("true");
    assertThat(scanProperties.getProperty(DatabaseConfig.CROSS_PARTITION_SCAN_FILTERING))
        .isEqualTo("true");
    assertThat(scanProperties.getProperty(DatabaseConfig.CROSS_PARTITION_SCAN_ORDERING))
        .isEqualTo("true");
  }

  @Test
  void buildScanProperties_crossPartitionScanDisabledGiven_shouldOverrideIt() throws Exception {
    // Arrange
    Properties properties = baseProperties();
    properties.setProperty(DatabaseConfig.CROSS_PARTITION_SCAN, "false");

    // Act
    Properties scanProperties = buildScanProperties(properties);

    // Assert
    assertThat(scanProperties.getProperty(DatabaseConfig.CROSS_PARTITION_SCAN)).isEqualTo("true");
  }

  @Test
  void buildScanProperties_defaultsGiven_shouldCapTheConnectionPools() throws Exception {
    // Arrange
    // The scan holds one page query at a time, so it must not inherit ScalarDB's much larger
    // defaults and double the connections the tool needs.

    // Act
    Properties scanProperties = buildScanProperties(baseProperties());

    // Assert
    assertThat(Integer.parseInt(scanProperties.getProperty(JdbcConfig.CONNECTION_POOL_MAX_TOTAL)))
        .isLessThan(JdbcConfig.DEFAULT_CONNECTION_POOL_MAX_TOTAL)
        .isPositive();
    assertThat(
            Integer.parseInt(
                scanProperties.getProperty(JdbcConfig.ADMIN_CONNECTION_POOL_MAX_TOTAL)))
        .isLessThan(JdbcConfig.DEFAULT_ADMIN_CONNECTION_POOL_MAX_TOTAL)
        .isPositive();
  }

  @Test
  void buildScanProperties_smallerPoolConfiguredGiven_shouldKeepIt() throws Exception {
    // Arrange
    Properties properties = baseProperties();
    properties.setProperty(JdbcConfig.CONNECTION_POOL_MAX_TOTAL, "1");

    // Act
    Properties scanProperties = buildScanProperties(properties);

    // Assert
    assertThat(scanProperties.getProperty(JdbcConfig.CONNECTION_POOL_MAX_TOTAL)).isEqualTo("1");
  }

  @Test
  void buildScanProperties_originalPropertiesGiven_shouldNotBeMutated() throws Exception {
    // Arrange
    Properties original = baseProperties();

    // Act
    buildScanProperties(original);

    // Assert
    assertThat(original.getProperty(DatabaseConfig.CROSS_PARTITION_SCAN)).isNull();
    assertThat(original.getProperty(JdbcConfig.CONNECTION_POOL_MAX_TOTAL)).isNull();
  }
}
