package com.scalar.dl.tools.scan.jdbc;

import com.scalar.db.config.DatabaseConfig;
import java.util.Properties;

/**
 * Provides JDBC configuration for integration tests from system properties.
 *
 * <p>System properties:
 *
 * <ul>
 *   <li>{@code scalardb.jdbc.url} - JDBC URL (default: {@code
 *       jdbc:postgresql://localhost:5432/postgres})
 *   <li>{@code scalardb.jdbc.username} - username (default: {@code postgres})
 *   <li>{@code scalardb.jdbc.password} - password (default: {@code postgres})
 * </ul>
 */
final class JdbcEnv {

  private static final String PROP_JDBC_URL = "scalardb.jdbc.url";
  private static final String PROP_JDBC_USERNAME = "scalardb.jdbc.username";
  private static final String PROP_JDBC_PASSWORD = "scalardb.jdbc.password";

  private static final String DEFAULT_JDBC_URL = "jdbc:postgresql://localhost:5432/postgres";
  private static final String DEFAULT_JDBC_USERNAME = "postgres";
  private static final String DEFAULT_JDBC_PASSWORD = "postgres";

  private JdbcEnv() {}

  static Properties getProperties() {
    Properties properties = new Properties();
    properties.setProperty(DatabaseConfig.STORAGE, "jdbc");
    properties.setProperty(
        DatabaseConfig.CONTACT_POINTS, System.getProperty(PROP_JDBC_URL, DEFAULT_JDBC_URL));
    properties.setProperty(
        DatabaseConfig.USERNAME, System.getProperty(PROP_JDBC_USERNAME, DEFAULT_JDBC_USERNAME));
    properties.setProperty(
        DatabaseConfig.PASSWORD, System.getProperty(PROP_JDBC_PASSWORD, DEFAULT_JDBC_PASSWORD));
    return properties;
  }
}
