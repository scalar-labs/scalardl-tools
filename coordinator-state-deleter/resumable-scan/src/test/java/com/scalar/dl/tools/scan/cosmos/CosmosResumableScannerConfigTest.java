package com.scalar.dl.tools.scan.cosmos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scalar.db.config.DatabaseConfig;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class CosmosResumableScannerConfigTest {

  private static DatabaseConfig createConfig(Properties extra) {
    Properties props = new Properties();
    props.setProperty(DatabaseConfig.STORAGE, "cosmos");
    props.setProperty(DatabaseConfig.CONTACT_POINTS, "https://localhost:8081");
    props.setProperty(DatabaseConfig.PASSWORD, "dummy");
    props.putAll(extra);
    return new DatabaseConfig(props);
  }

  @Test
  void constructor_noExtraPropertiesSet_shouldUseDefaults() {
    // Arrange
    DatabaseConfig databaseConfig = createConfig(new Properties());

    // Act
    CosmosResumableScannerConfig config = new CosmosResumableScannerConfig(databaseConfig);

    // Assert
    assertThat(config.getMaxWorkerThreads()).isEqualTo(32);
    assertThat(config.getMaxItemCount()).isEqualTo(100);
  }

  @Test
  void constructor_extraPropertiesSet_shouldUseCustomValues() {
    // Arrange
    Properties extra = new Properties();
    extra.setProperty(CosmosResumableScannerConfig.PROP_MAX_SCAN_THREADS, "16");
    extra.setProperty(CosmosResumableScannerConfig.PROP_SCAN_PAGE_SIZE, "50");
    DatabaseConfig databaseConfig = createConfig(extra);

    // Act
    CosmosResumableScannerConfig config = new CosmosResumableScannerConfig(databaseConfig);

    // Assert
    assertThat(config.getMaxWorkerThreads()).isEqualTo(16);
    assertThat(config.getMaxItemCount()).isEqualTo(50);
  }

  @Test
  void constructor_invalidIntegerValue_shouldThrowIllegalArgumentException() {
    // Arrange
    Properties extra = new Properties();
    extra.setProperty(CosmosResumableScannerConfig.PROP_MAX_SCAN_THREADS, "not-a-number");
    DatabaseConfig databaseConfig = createConfig(extra);

    // Act & Assert
    assertThatThrownBy(() -> new CosmosResumableScannerConfig(databaseConfig))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(CosmosResumableScannerConfig.PROP_MAX_SCAN_THREADS);
  }

  @Test
  void constructor_zeroValue_shouldThrowIllegalArgumentException() {
    // Arrange
    Properties extra = new Properties();
    extra.setProperty(CosmosResumableScannerConfig.PROP_MAX_SCAN_THREADS, "0");
    DatabaseConfig databaseConfig = createConfig(extra);

    // Act & Assert
    assertThatThrownBy(() -> new CosmosResumableScannerConfig(databaseConfig))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(CosmosResumableScannerConfig.PROP_MAX_SCAN_THREADS);
  }

  @Test
  void constructor_negativeValue_shouldThrowIllegalArgumentException() {
    // Arrange
    Properties extra = new Properties();
    extra.setProperty(CosmosResumableScannerConfig.PROP_SCAN_PAGE_SIZE, "-1");
    DatabaseConfig databaseConfig = createConfig(extra);

    // Act & Assert
    assertThatThrownBy(() -> new CosmosResumableScannerConfig(databaseConfig))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(CosmosResumableScannerConfig.PROP_SCAN_PAGE_SIZE);
  }
}
