package com.scalar.dl.tools.cleanup;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.collect.ImmutableMap;
import com.scalar.db.config.DatabaseConfig;
import com.scalar.db.storage.cosmos.CosmosConfig;
import com.scalar.db.storage.jdbc.JdbcConfig;
import com.scalar.db.storage.multistorage.MultiStorageConfig;
import com.scalar.db.transaction.consensuscommit.ConsensusCommitConfig;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class StorageConfigFingerprintTest {

  private static final String COSMOS_URI = "https://example.documents.azure.com:443/";
  private static final String JDBC_URL = "jdbc:postgresql://localhost:5432/postgres";

  private static Properties cosmosProperties() {
    Properties props = new Properties();
    props.setProperty(DatabaseConfig.STORAGE, CosmosConfig.STORAGE_NAME);
    props.setProperty(DatabaseConfig.CONTACT_POINTS, COSMOS_URI);
    props.setProperty(DatabaseConfig.PASSWORD, "cosmos-key");
    return props;
  }

  private static Properties multiStorageProperties() {
    Properties props = new Properties();
    props.setProperty(DatabaseConfig.STORAGE, MultiStorageConfig.STORAGE_NAME);
    props.setProperty(MultiStorageConfig.STORAGES, "cosmos,postgresql");
    props.setProperty(MultiStorageConfig.STORAGES + ".cosmos.storage", CosmosConfig.STORAGE_NAME);
    props.setProperty(MultiStorageConfig.STORAGES + ".cosmos.contact_points", COSMOS_URI);
    props.setProperty(MultiStorageConfig.STORAGES + ".cosmos.password", "cosmos-key");
    props.setProperty(MultiStorageConfig.STORAGES + ".postgresql.storage", JdbcConfig.STORAGE_NAME);
    props.setProperty(MultiStorageConfig.STORAGES + ".postgresql.contact_points", JDBC_URL);
    props.setProperty(MultiStorageConfig.STORAGES + ".postgresql.username", "postgres");
    props.setProperty(MultiStorageConfig.STORAGES + ".postgresql.password", "postgres-password");
    props.setProperty(MultiStorageConfig.NAMESPACE_MAPPING, "ns2:postgresql,ns1:postgresql");
    props.setProperty(MultiStorageConfig.DEFAULT_STORAGE, "cosmos");
    return props;
  }

  private static Map<String, String> fingerprintOf(Properties props) {
    return StorageConfigFingerprint.create(new DatabaseConfig(props));
  }

  @Test
  void create_cosmosGiven_shouldReturnLocationOfTables() {
    // Arrange Act
    Map<String, String> fingerprint = fingerprintOf(cosmosProperties());

    // Assert
    assertThat(fingerprint)
        .containsExactly(
            Map.entry("consensus_commit.coordinator.namespace", "coordinator"),
            Map.entry("contact_points", COSMOS_URI),
            Map.entry("cosmos.table_metadata.database", "scalardb"),
            Map.entry("storage", "cosmos"));
  }

  @Test
  void create_multiStorageGiven_shouldReturnLocationOfTablesInEveryStorage() {
    // Arrange Act
    Map<String, String> fingerprint = fingerprintOf(multiStorageProperties());

    // Assert
    // The namespace mapping is sorted, and the credentials are not included.
    assertThat(fingerprint)
        .containsExactly(
            Map.entry("consensus_commit.coordinator.namespace", "coordinator"),
            Map.entry("multi_storage.default_storage", "cosmos"),
            Map.entry("multi_storage.namespace_mapping", "ns1:postgresql,ns2:postgresql"),
            Map.entry("multi_storage.storages.cosmos.contact_points", COSMOS_URI),
            Map.entry("multi_storage.storages.cosmos.cosmos.table_metadata.database", "scalardb"),
            Map.entry("multi_storage.storages.cosmos.storage", "cosmos"),
            Map.entry("multi_storage.storages.postgresql.contact_points", JDBC_URL),
            Map.entry("multi_storage.storages.postgresql.jdbc.table_metadata.schema", "scalardb"),
            Map.entry("multi_storage.storages.postgresql.storage", "jdbc"),
            Map.entry("storage", "multi-storage"));
  }

  @Test
  void create_customLocationsGiven_shouldReturnThem() {
    // Arrange
    Properties props = multiStorageProperties();
    props.setProperty(ConsensusCommitConfig.COORDINATOR_NAMESPACE, "my_coordinator");
    props.setProperty(
        MultiStorageConfig.STORAGES + ".cosmos.cosmos.table_metadata.database", "cosmos_meta");
    props.setProperty(
        MultiStorageConfig.STORAGES + ".postgresql.jdbc.table_metadata.schema", "jdbc_meta");

    // Act
    Map<String, String> fingerprint = fingerprintOf(props);

    // Assert
    assertThat(fingerprint)
        .containsEntry("consensus_commit.coordinator.namespace", "my_coordinator")
        .containsEntry(
            "multi_storage.storages.cosmos.cosmos.table_metadata.database", "cosmos_meta")
        .containsEntry("multi_storage.storages.postgresql.jdbc.table_metadata.schema", "jdbc_meta");
  }

  @Test
  void create_equivalentConfigurationsGiven_shouldReturnSameFingerprint() {
    // Arrange
    Properties props = multiStorageProperties();
    Properties equivalentProps = multiStorageProperties();
    // Another order of the namespace mapping entries.
    equivalentProps.setProperty(
        MultiStorageConfig.NAMESPACE_MAPPING, "ns1:postgresql,ns2:postgresql");
    // The default locations of the table metadata and the Coordinator namespace, set explicitly.
    equivalentProps.setProperty(
        MultiStorageConfig.STORAGES + ".cosmos.cosmos.table_metadata.database", "scalardb");
    equivalentProps.setProperty(
        MultiStorageConfig.STORAGES + ".postgresql.jdbc.table_metadata.schema", "scalardb");
    equivalentProps.setProperty(ConsensusCommitConfig.COORDINATOR_NAMESPACE, "coordinator");
    // Other credentials and settings that do not change where the tables are.
    equivalentProps.setProperty(MultiStorageConfig.STORAGES + ".cosmos.password", "rotated-key");
    equivalentProps.setProperty(MultiStorageConfig.STORAGES + ".postgresql.username", "other");
    equivalentProps.setProperty(
        MultiStorageConfig.STORAGES + ".postgresql.password", "rotated-password");
    equivalentProps.setProperty(
        MultiStorageConfig.STORAGES + ".postgresql.jdbc.connection_pool.max_total", "10");
    equivalentProps.setProperty("scalar.dl.tools.scan.jdbc.page_size", "50");

    // Act Assert
    assertThat(fingerprintOf(equivalentProps)).isEqualTo(fingerprintOf(props));
  }

  @Test
  void create_changedNamespaceMappingGiven_shouldReturnAnotherFingerprint() {
    // Arrange
    Properties props = multiStorageProperties();
    Properties changedProps = multiStorageProperties();
    changedProps.setProperty(
        MultiStorageConfig.NAMESPACE_MAPPING, "ns1:postgresql,ns2:postgresql,ns3:postgresql");

    // Act Assert
    assertThat(fingerprintOf(changedProps)).isNotEqualTo(fingerprintOf(props));
  }

  @Test
  void create_escapedCommaInContactPointGiven_shouldDifferFromSeparateContactPoints() {
    // Arrange
    Properties props = multiStorageProperties();
    props.setProperty(
        MultiStorageConfig.STORAGES + ".postgresql.contact_points",
        "jdbc:mysql://h1:3306\\,h2:3306/db");
    Properties unescapedProps = multiStorageProperties();
    unescapedProps.setProperty(
        MultiStorageConfig.STORAGES + ".postgresql.contact_points",
        "jdbc:mysql://h1:3306,h2:3306/db");

    // Act
    Map<String, String> fingerprint = fingerprintOf(props);
    Map<String, String> unescapedFingerprint = fingerprintOf(unescapedProps);

    // Assert
    assertThat(fingerprint)
        .containsEntry(
            "multi_storage.storages.postgresql.contact_points",
            "jdbc:mysql://h1:3306\\,h2:3306/db");
    assertThat(unescapedFingerprint)
        .containsEntry(
            "multi_storage.storages.postgresql.contact_points", "jdbc:mysql://h1:3306,h2:3306/db");
    assertThat(fingerprint).isNotEqualTo(unescapedFingerprint);
  }

  @Test
  void describeDifferences_changedAddedAndRemovedKeysGiven_shouldDescribeThemInKeyOrder() {
    // Arrange
    Map<String, String> expected = ImmutableMap.of("a", "1", "b", "2", "c", "3");
    Map<String, String> actual = ImmutableMap.of("a", "1", "b", "20", "d", "4");

    // Act
    String differences = StorageConfigFingerprint.describeDifferences(expected, actual);

    // Assert
    assertThat(differences).isEqualTo("b: '2' -> '20', c: '3' -> (none), d: (none) -> '4'");
  }

  @Test
  void describeDifferences_switchToMultiStorageGiven_shouldDescribeEveryChangedKey() {
    // Arrange
    Map<String, String> expected = fingerprintOf(cosmosProperties());
    Map<String, String> actual = fingerprintOf(multiStorageProperties());

    // Act
    String differences = StorageConfigFingerprint.describeDifferences(expected, actual);

    // Assert
    assertThat(differences)
        .contains("storage: 'cosmos' -> 'multi-storage'")
        .contains("contact_points: ****** -> (none)")
        .contains("multi_storage.storages.postgresql.contact_points: (none) -> ******")
        .contains("multi_storage.namespace_mapping: (none) -> 'ns1:postgresql,ns2:postgresql'")
        .doesNotContain("consensus_commit.coordinator.namespace")
        .doesNotContain(COSMOS_URI)
        .doesNotContain(JDBC_URL);
  }

  @Test
  void describeDifferences_changedContactPointsGiven_shouldMaskTheirValues() {
    // Arrange
    Properties props = multiStorageProperties();
    props.setProperty(
        MultiStorageConfig.STORAGES + ".postgresql.contact_points",
        "jdbc:postgresql://db1:5432/postgres?user=postgres&password=old-secret");
    Properties changedProps = multiStorageProperties();
    changedProps.setProperty(
        MultiStorageConfig.STORAGES + ".postgresql.contact_points",
        "jdbc:postgresql://db2:5432/postgres?user=postgres&password=new-secret");

    // Act
    String differences =
        StorageConfigFingerprint.describeDifferences(
            fingerprintOf(props), fingerprintOf(changedProps));

    // Assert
    assertThat(differences)
        .isEqualTo("multi_storage.storages.postgresql.contact_points: ****** -> ******");
    assertThat(differences).doesNotContain("secret").doesNotContain("db1").doesNotContain("db2");
  }
}
