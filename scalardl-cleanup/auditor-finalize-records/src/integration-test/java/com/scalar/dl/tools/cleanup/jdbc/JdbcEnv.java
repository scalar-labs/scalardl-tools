package com.scalar.dl.tools.cleanup.jdbc;

import com.scalar.db.config.DatabaseConfig;
import java.util.Properties;

/**
 * Provides JDBC configuration for integration tests from system properties.
 *
 * <p>Required system properties:
 *
 * <ul>
 *   <li>{@code scalardb.jdbc.url} - JDBC URL, which also selects the database engine
 *   <li>{@code scalardb.jdbc.username} - database user
 *   <li>{@code scalardb.jdbc.password} - database password
 * </ul>
 *
 * <p>MySQL and PostgreSQL differ only in the URL, so one environment serves both. MySQL 8 defaults
 * to caching_sha2_password, whose password exchange the driver refuses over a plain connection, so
 * a MySQL URL needs {@code ?allowPublicKeyRetrieval=true&useSSL=false} (or a TLS-enabled server).
 */
final class JdbcEnv {

  private static final String PROP_JDBC_URL = "scalardb.jdbc.url";
  private static final String PROP_JDBC_USERNAME = "scalardb.jdbc.username";
  private static final String PROP_JDBC_PASSWORD = "scalardb.jdbc.password";

  private JdbcEnv() {}

  static Properties getProperties() {
    String url = System.getProperty(PROP_JDBC_URL);
    String username = System.getProperty(PROP_JDBC_USERNAME);
    String password = System.getProperty(PROP_JDBC_PASSWORD);
    if (url == null || username == null) {
      throw new IllegalStateException(
          "System properties " + PROP_JDBC_URL + " and " + PROP_JDBC_USERNAME + " must be set.");
    }

    Properties properties = new Properties();
    properties.setProperty(DatabaseConfig.STORAGE, "jdbc");
    properties.setProperty(DatabaseConfig.CONTACT_POINTS, url);
    properties.setProperty(DatabaseConfig.USERNAME, username);
    properties.setProperty(DatabaseConfig.PASSWORD, password == null ? "" : password);

    return properties;
  }
}
