package com.scalar.dl.tools.cleanup;

import com.scalar.db.config.ConfigUtils;
import com.scalar.db.config.DatabaseConfig;
import com.scalar.db.storage.cosmos.CosmosConfig;
import com.scalar.db.storage.jdbc.JdbcConfig;
import com.scalar.db.storage.multistorage.MultiStorageConfig;
import com.scalar.db.transaction.consensuscommit.ConsensusCommitConfig;
import com.scalar.db.transaction.consensuscommit.CoordinatorStateAccessor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Builds the fingerprint of the storage configuration, which {@code ledger-finalize-records} keeps
 * in its checkpoint data to detect a configuration that changed after the run started.
 *
 * <p>The fingerprint has only the settings that decide where the tables are: the storage types,
 * their contact points, where their table metadata is, the namespace mapping, the default storage,
 * and the Coordinator namespace. It does not have credentials, so it can be kept in the checkpoint
 * data as plain text, and it does not have the settings that do not change where the tables are,
 * such as the connection pool settings. Note that the username is not included either, although it
 * can decide the database if the JDBC URL does not specify one, for example with PostgreSQL. The
 * values are normalized, so that equivalent configurations have the same fingerprint, for example
 * regardless of the order of the namespace mapping entries or whether the default location of the
 * table metadata is set explicitly.
 */
final class StorageConfigFingerprint {

  private static final String MULTI_STORAGE_PREFIX = "multi_storage.";
  private static final String CONTACT_POINTS = "contact_points";
  private static final String NONE = "(none)";
  private static final String MASKED_VALUE = "******";

  private StorageConfigFingerprint() {}

  /**
   * Returns the fingerprint of the given configuration, as a map sorted by key. The keys are named
   * after the properties without the {@code scalar.db.} prefix.
   *
   * @param databaseConfig the ScalarDB database configuration, which must have passed the storage
   *     validation
   * @return the fingerprint
   */
  static Map<String, String> create(DatabaseConfig databaseConfig) {
    Map<String, String> fingerprint = new TreeMap<>();
    putStorage(fingerprint, "", databaseConfig);
    fingerprint.put(
        "consensus_commit.coordinator.namespace",
        ConfigUtils.getString(
            databaseConfig.getProperties(),
            ConsensusCommitConfig.COORDINATOR_NAMESPACE,
            CoordinatorStateAccessor.NAMESPACE));

    if (MultiStorageConfig.STORAGE_NAME.equals(databaseConfig.getStorage())) {
      MultiStorageConfig multiStorageConfig = new MultiStorageConfig(databaseConfig);
      for (Map.Entry<String, Properties> entry :
          multiStorageConfig.getDatabasePropertiesMap().entrySet()) {
        putStorage(
            fingerprint,
            MULTI_STORAGE_PREFIX + "storages." + entry.getKey() + ".",
            new DatabaseConfig(entry.getValue()));
      }
      fingerprint.put(
          MULTI_STORAGE_PREFIX + "default_storage", multiStorageConfig.getDefaultStorage());
      fingerprint.put(
          MULTI_STORAGE_PREFIX + "namespace_mapping",
          multiStorageConfig.getNamespaceStorageMap().entrySet().stream()
              .map(e -> e.getKey() + ":" + e.getValue())
              .sorted()
              .collect(Collectors.joining(",")));
    }
    return Collections.unmodifiableMap(fingerprint);
  }

  /**
   * Puts the settings of a single storage. For multi-storage itself, only its storage type is put,
   * because it has no contact points or table metadata of its own.
   */
  private static void putStorage(
      Map<String, String> fingerprint, String prefix, DatabaseConfig databaseConfig) {
    String storage = databaseConfig.getStorage();
    fingerprint.put(prefix + "storage", storage);
    if (MultiStorageConfig.STORAGE_NAME.equals(storage)) {
      return;
    }
    // The contact points are escaped before they are joined, so that, for example, a single
    // contact point "a,b" (written as "a\,b") and two contact points "a" and "b" differ.
    fingerprint.put(
        prefix + CONTACT_POINTS,
        databaseConfig.getContactPoints().stream()
            .map(c -> c.replace("\\", "\\\\").replace(",", "\\,"))
            .collect(Collectors.joining(",")));
    // The admins use the system namespace if the location of the table metadata is not set.
    Properties props = databaseConfig.getProperties();
    if (CosmosConfig.STORAGE_NAME.equals(storage)) {
      fingerprint.put(
          prefix + "cosmos.table_metadata.database",
          ConfigUtils.getString(
              props,
              CosmosConfig.TABLE_METADATA_DATABASE,
              DatabaseConfig.DEFAULT_SYSTEM_NAMESPACE_NAME));
    } else if (JdbcConfig.STORAGE_NAME.equals(storage)) {
      fingerprint.put(
          prefix + "jdbc.table_metadata.schema",
          ConfigUtils.getString(
              props,
              JdbcConfig.TABLE_METADATA_SCHEMA,
              DatabaseConfig.DEFAULT_SYSTEM_NAMESPACE_NAME));
    }
  }

  /**
   * Describes the differences between two fingerprints, such as {@code
   * multi_storage.namespace_mapping: 'ns1:postgresql' -> 'ns1:postgresql,ns2:postgresql'}. A key
   * that only one of them has is shown with {@code (none)} on the other side. The values of the
   * contact points are masked, such as {@code contact_points: ****** -> ******}, because a JDBC URL
   * can include credentials in its parameters.
   *
   * @param expected the fingerprint that the run started with
   * @param actual the fingerprint of the current configuration
   * @return the differences separated by commas, in the order of the keys
   */
  static String describeDifferences(Map<String, String> expected, Map<String, String> actual) {
    SortedSet<String> keys = new TreeSet<>(expected.keySet());
    keys.addAll(actual.keySet());
    List<String> differences = new ArrayList<>();
    for (String key : keys) {
      String expectedValue = expected.get(key);
      String actualValue = actual.get(key);
      if (!Objects.equals(expectedValue, actualValue)) {
        boolean masked = key.endsWith(CONTACT_POINTS);
        differences.add(
            key
                + ": "
                + describeValue(expectedValue, masked)
                + " -> "
                + describeValue(actualValue, masked));
      }
    }
    return String.join(", ", differences);
  }

  private static String describeValue(String value, boolean masked) {
    if (value == null) {
      return NONE;
    }
    return masked ? MASKED_VALUE : "'" + value + "'";
  }
}
