package com.scalar.dl.tools.scan.cosmos;

import com.scalar.db.config.DatabaseConfig;
import java.util.Properties;

/**
 * Provides Cosmos DB configuration for integration tests from system properties.
 *
 * <p>Required system properties:
 *
 * <ul>
 *   <li>{@code scalardb.cosmos.uri} - Cosmos DB endpoint
 *   <li>{@code scalardb.cosmos.password} - Cosmos DB authentication key
 * </ul>
 */
final class CosmosEnv {

  private static final String PROP_COSMOS_URI = "scalardb.cosmos.uri";
  private static final String PROP_COSMOS_PASSWORD = "scalardb.cosmos.password";

  private CosmosEnv() {}

  static Properties getProperties() {
    String contactPoint = System.getProperty(PROP_COSMOS_URI);
    String password = System.getProperty(PROP_COSMOS_PASSWORD);
    if (contactPoint == null || password == null) {
      throw new IllegalStateException(
          "System properties "
              + PROP_COSMOS_URI
              + " and "
              + PROP_COSMOS_PASSWORD
              + " must be set.");
    }

    Properties properties = new Properties();
    properties.setProperty(DatabaseConfig.STORAGE, "cosmos");
    properties.setProperty(DatabaseConfig.CONTACT_POINTS, contactPoint);
    properties.setProperty(DatabaseConfig.PASSWORD, password);

    return properties;
  }
}
