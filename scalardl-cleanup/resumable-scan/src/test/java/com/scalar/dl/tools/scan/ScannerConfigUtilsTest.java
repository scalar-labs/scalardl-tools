package com.scalar.dl.tools.scan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scalar.db.config.DatabaseConfig;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class ScannerConfigUtilsTest {

  private static final String KEY = "scalar.dl.tools.scan.test.value";

  private static DatabaseConfig databaseConfig(Properties extra) {
    Properties props = new Properties();
    props.setProperty(DatabaseConfig.STORAGE, "jdbc");
    props.setProperty(DatabaseConfig.CONTACT_POINTS, "jdbc:postgresql://localhost:5432/");
    props.putAll(extra);
    return new DatabaseConfig(props);
  }

  private static DatabaseConfig databaseConfig(String value) {
    Properties extra = new Properties();
    extra.setProperty(KEY, value);
    return databaseConfig(extra);
  }

  @Test
  void getPositiveIntProperty_whenPropertyNotSet_shouldReturnDefault() {
    assertThat(ScannerConfigUtils.getPositiveIntProperty(databaseConfig(new Properties()), KEY, 10))
        .isEqualTo(10);
  }

  @Test
  void getPositiveIntProperty_positiveValueGiven_shouldReturnIt() {
    assertThat(ScannerConfigUtils.getPositiveIntProperty(databaseConfig("5"), KEY, 10))
        .isEqualTo(5);
  }

  @Test
  void getPositiveIntProperty_nonIntegerValueGiven_shouldThrowIllegalArgumentException() {
    assertThatThrownBy(
            () ->
                ScannerConfigUtils.getPositiveIntProperty(databaseConfig("not-a-number"), KEY, 10))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(KEY);
  }

  @Test
  void getPositiveIntProperty_zeroGiven_shouldThrowIllegalArgumentException() {
    assertThatThrownBy(
            () -> ScannerConfigUtils.getPositiveIntProperty(databaseConfig("0"), KEY, 10))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(KEY);
  }

  @Test
  void getPositiveIntProperty_negativeValueGiven_shouldThrowIllegalArgumentException() {
    assertThatThrownBy(
            () -> ScannerConfigUtils.getPositiveIntProperty(databaseConfig("-1"), KEY, 10))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(KEY);
  }
}
