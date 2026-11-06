package com.scalar.dl.tools.cleanup.multistorage;

import com.scalar.db.config.DatabaseConfig;
import com.scalar.db.storage.multistorage.MultiStorageConfig;
import java.util.Properties;

/**
 * Provides multi-storage configuration for integration tests from system properties.
 *
 * <p>The configuration has two storages: Cosmos DB as the default storage, and a JDBC database that
 * the given namespace is mapped to. It follows a deployment where Ledger uses Cosmos DB and shares
 * the Coordinator table with ScalarDB Cluster, which also has tables in a JDBC database.
 *
 * <p>System properties:
 *
 * <ul>
 *   <li>{@code scalardb.cosmos.uri} - Cosmos DB account URI. Required.
 *   <li>{@code scalardb.cosmos.password} - Cosmos DB account key. Required.
 *   <li>{@code scalardb.jdbc.url} - JDBC URL (default: {@code
 *       jdbc:postgresql://localhost:5432/postgres})
 *   <li>{@code scalardb.jdbc.username} - username (default: {@code postgres})
 *   <li>{@code scalardb.jdbc.password} - password (default: {@code postgres})
 * </ul>
 */
final class MultiStorageEnv {

  private static final String PROP_COSMOS_URI = "scalardb.cosmos.uri";
  private static final String PROP_COSMOS_PASSWORD = "scalardb.cosmos.password";
  private static final String PROP_JDBC_URL = "scalardb.jdbc.url";
  private static final String PROP_JDBC_USERNAME = "scalardb.jdbc.username";
  private static final String PROP_JDBC_PASSWORD = "scalardb.jdbc.password";

  private static final String DEFAULT_JDBC_URL = "jdbc:postgresql://localhost:5432/postgres";
  private static final String DEFAULT_JDBC_USERNAME = "postgres";
  private static final String DEFAULT_JDBC_PASSWORD = "postgres";

  private static final String COSMOS_STORAGE = "cosmos_db";
  private static final String JDBC_STORAGE = "jdbc_db";

  private MultiStorageEnv() {}

  static Properties getProperties(String namespaceInJdbcStorage) {
    String cosmosUri = System.getProperty(PROP_COSMOS_URI);
    String cosmosPassword = System.getProperty(PROP_COSMOS_PASSWORD);
    if (cosmosUri == null || cosmosPassword == null) {
      throw new IllegalStateException(
          "System properties "
              + PROP_COSMOS_URI
              + " and "
              + PROP_COSMOS_PASSWORD
              + " must be set.");
    }

    Properties properties = new Properties();
    properties.setProperty(DatabaseConfig.STORAGE, MultiStorageConfig.STORAGE_NAME);
    properties.setProperty(MultiStorageConfig.STORAGES, COSMOS_STORAGE + "," + JDBC_STORAGE);

    String cosmosPrefix = MultiStorageConfig.STORAGES + "." + COSMOS_STORAGE + ".";
    properties.setProperty(cosmosPrefix + "storage", "cosmos");
    properties.setProperty(cosmosPrefix + "contact_points", cosmosUri);
    properties.setProperty(cosmosPrefix + "password", cosmosPassword);

    String jdbcPrefix = MultiStorageConfig.STORAGES + "." + JDBC_STORAGE + ".";
    properties.setProperty(jdbcPrefix + "storage", "jdbc");
    properties.setProperty(
        jdbcPrefix + "contact_points", System.getProperty(PROP_JDBC_URL, DEFAULT_JDBC_URL));
    properties.setProperty(
        jdbcPrefix + "username", System.getProperty(PROP_JDBC_USERNAME, DEFAULT_JDBC_USERNAME));
    properties.setProperty(
        jdbcPrefix + "password", System.getProperty(PROP_JDBC_PASSWORD, DEFAULT_JDBC_PASSWORD));

    properties.setProperty(
        MultiStorageConfig.NAMESPACE_MAPPING, namespaceInJdbcStorage + ":" + JDBC_STORAGE);
    properties.setProperty(MultiStorageConfig.DEFAULT_STORAGE, COSMOS_STORAGE);
    return properties;
  }
}
