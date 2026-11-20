package com.scalar.dl.tools.cleanup;

import static org.assertj.core.api.Assertions.assertThat;

import com.scalar.db.api.DistributedStorage;
import com.scalar.db.api.DistributedStorageAdmin;
import com.scalar.db.api.Put;
import com.scalar.db.api.TableMetadata;
import com.scalar.db.io.DataType;
import com.scalar.db.io.Key;
import com.scalar.db.service.StorageFactory;
import com.scalar.dl.tools.common.AuditorInternalValues;
import com.scalar.dl.tools.common.VerificationResult;
import java.util.Properties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Integration test for {@link AuditorVerifyOrchestrator} against real ScalarDB storage.
 *
 * <p>The orchestrator is created with {@link AuditorVerifyOrchestrator#create(Properties)} from the
 * storage settings and the Auditor base namespace only, as the verify command does, so that the
 * namespace discovery and the scans run against the real {@code asset_lock} tables.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AuditorVerifyOrchestratorIntegrationTestBase {

  private static final String NAMESPACE = "it_auditor_verify";
  private static final String NS1_PHYSICAL =
      AuditorInternalValues.resolveNamespace(NAMESPACE, "ns1");
  private static final String NS2_PHYSICAL =
      AuditorInternalValues.resolveNamespace(NAMESPACE, "ns2");
  private static final long OLD_TIMESTAMP = 1L;

  private DistributedStorageAdmin storageAdmin;
  private DistributedStorage storage;

  protected abstract Properties getProperties();

  @BeforeAll
  void setUpAll() throws Exception {
    StorageFactory storageFactory = StorageFactory.create(getProperties());
    storageAdmin = storageFactory.getStorageAdmin();
    storage = storageFactory.getStorage();

    createAssetLockTableIn(NAMESPACE);
    createAssetLockTableIn(NS1_PHYSICAL);
    createAssetLockTableIn(NS2_PHYSICAL);
    createNamespaceRegistryTable();
    putRegisteredNamespace("ns1");
    putRegisteredNamespace("ns2");
  }

  @AfterAll
  void tearDownAll() throws Exception {
    try {
      storageAdmin.dropTable(NS2_PHYSICAL, AuditorInternalValues.ASSET_LOCK_TABLE_NAME, true);
      storageAdmin.dropNamespace(NS2_PHYSICAL, true);
      storageAdmin.dropTable(NS1_PHYSICAL, AuditorInternalValues.ASSET_LOCK_TABLE_NAME, true);
      storageAdmin.dropNamespace(NS1_PHYSICAL, true);
      storageAdmin.dropTable(NAMESPACE, AuditorInternalValues.NAMESPACE_TABLE_NAME, true);
      storageAdmin.dropTable(NAMESPACE, AuditorInternalValues.ASSET_LOCK_TABLE_NAME, true);
      storageAdmin.dropNamespace(NAMESPACE, true);
    } finally {
      storageAdmin.close();
      storage.close();
    }
  }

  @BeforeEach
  void setUp() throws Exception {
    storageAdmin.truncateTable(NAMESPACE, AuditorInternalValues.ASSET_LOCK_TABLE_NAME);
    storageAdmin.truncateTable(NS1_PHYSICAL, AuditorInternalValues.ASSET_LOCK_TABLE_NAME);
    storageAdmin.truncateTable(NS2_PHYSICAL, AuditorInternalValues.ASSET_LOCK_TABLE_NAME);
  }

  private void createAssetLockTableIn(String namespace) throws Exception {
    storageAdmin.createNamespace(namespace, true);
    TableMetadata metadata =
        TableMetadata.newBuilder()
            .addColumn(AuditorInternalValues.ASSET_LOCK_TABLE_ID_COLUMN_NAME, DataType.TEXT)
            .addColumn(AuditorInternalValues.ASSET_LOCK_TABLE_LOCK_TYPE_COLUMN_NAME, DataType.INT)
            .addColumn(
                AuditorInternalValues.ASSET_LOCK_TABLE_LAST_UPDATED_AT_COLUMN_NAME, DataType.BIGINT)
            .addPartitionKey(AuditorInternalValues.ASSET_LOCK_TABLE_ID_COLUMN_NAME)
            .build();
    storageAdmin.createTable(
        namespace, AuditorInternalValues.ASSET_LOCK_TABLE_NAME, metadata, true);
  }

  private void createNamespaceRegistryTable() throws Exception {
    TableMetadata metadata =
        TableMetadata.newBuilder()
            .addColumn(AuditorInternalValues.NAMESPACE_TABLE_PARTITION_ID_COLUMN_NAME, DataType.INT)
            .addColumn(AuditorInternalValues.NAMESPACE_TABLE_NAME_COLUMN_NAME, DataType.TEXT)
            .addPartitionKey(AuditorInternalValues.NAMESPACE_TABLE_PARTITION_ID_COLUMN_NAME)
            .addClusteringKey(AuditorInternalValues.NAMESPACE_TABLE_NAME_COLUMN_NAME)
            .build();
    storageAdmin.createTable(NAMESPACE, AuditorInternalValues.NAMESPACE_TABLE_NAME, metadata, true);
  }

  private void putRegisteredNamespace(String logicalNamespace) throws Exception {
    Put put =
        Put.newBuilder()
            .namespace(NAMESPACE)
            .table(AuditorInternalValues.NAMESPACE_TABLE_NAME)
            .partitionKey(
                Key.ofInt(
                    AuditorInternalValues.NAMESPACE_TABLE_PARTITION_ID_COLUMN_NAME,
                    AuditorInternalValues.NAMESPACE_TABLE_DEFAULT_PARTITION_ID))
            .clusteringKey(
                Key.ofText(
                    AuditorInternalValues.NAMESPACE_TABLE_NAME_COLUMN_NAME, logicalNamespace))
            .build();
    storage.put(put);
  }

  private void putLockRecord(String namespace, String assetId, int lockType, long lastUpdatedAt)
      throws Exception {
    Put put =
        Put.newBuilder()
            .namespace(namespace)
            .table(AuditorInternalValues.ASSET_LOCK_TABLE_NAME)
            .partitionKey(
                Key.ofText(AuditorInternalValues.ASSET_LOCK_TABLE_ID_COLUMN_NAME, assetId))
            .intValue(AuditorInternalValues.ASSET_LOCK_TABLE_LOCK_TYPE_COLUMN_NAME, lockType)
            .bigIntValue(
                AuditorInternalValues.ASSET_LOCK_TABLE_LAST_UPDATED_AT_COLUMN_NAME, lastUpdatedAt)
            .build();
    storage.put(put);
  }

  /** Runs the verification with a newly created orchestrator, as a single run of the command. */
  private VerificationResult verify() throws Exception {
    Properties props = new Properties();
    props.putAll(getProperties());
    props.setProperty(AuditorInternalValues.AUDITOR_NAMESPACE_PROPERTY, NAMESPACE);
    try (AuditorVerifyOrchestrator orchestrator = AuditorVerifyOrchestrator.create(props)) {
      return orchestrator.execute();
    }
  }

  @Test
  public void execute_writeLockInContextNamespaceGiven_shouldFailUntilTheLockIsReleased()
      throws Exception {
    // Arrange — released and read locks in every namespace, and a single write lock in the last
    // one, taken just now.
    for (String namespace : new String[] {NAMESPACE, NS1_PHYSICAL, NS2_PHYSICAL}) {
      for (int i = 0; i < 4; i++) {
        putLockRecord(
            namespace, "released-" + i, AuditorInternalValues.LOCK_TYPE_NONE, OLD_TIMESTAMP);
        putLockRecord(namespace, "read-" + i, AuditorInternalValues.LOCK_TYPE_READ, OLD_TIMESTAMP);
      }
    }
    putLockRecord(
        NS2_PHYSICAL, "held", AuditorInternalValues.LOCK_TYPE_WRITE, System.currentTimeMillis());

    // Act & Assert — the write lock is found.
    assertThat(verify()).isEqualTo(VerificationResult.FAILED);

    // Act & Assert — once the lock is released, a new run passes; the remaining read locks are
    // not reported.
    putLockRecord(
        NS2_PHYSICAL, "held", AuditorInternalValues.LOCK_TYPE_NONE, System.currentTimeMillis());
    assertThat(verify()).isEqualTo(VerificationResult.PASSED);
  }
}
