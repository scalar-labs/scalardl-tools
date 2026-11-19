package com.scalar.dl.tools.cleanup.multistorage;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.api.TableMetadata;
import com.scalar.db.io.DataType;
import com.scalar.db.service.TransactionFactory;
import com.scalar.db.storage.jdbc.JdbcConfig;
import com.scalar.db.storage.multistorage.MultiStorageConfig;
import com.scalar.dl.tools.cleanup.LedgerFinalizeOrchestrator;
import com.scalar.dl.tools.cleanup.LedgerFinalizeOrchestratorIntegrationTestBase;
import com.scalar.dl.tools.common.ScalarDlCleanupError;
import com.scalar.dl.tools.common.ScalarDlCleanupException;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs the tests on multi-storage with Cosmos DB and a JDBC database. The Coordinator table and
 * ScalarDL's tables are in Cosmos DB, while the tables in {@code OTHER_NAMESPACE} are in the JDBC
 * database.
 *
 * <p>It also covers the validation of the namespace mapping against the real storages, because the
 * validation depends on how each storage lists its namespaces.
 */
class MultiStorageLedgerFinalizeOrchestratorIntegrationTest
    extends LedgerFinalizeOrchestratorIntegrationTestBase {

  @Override
  protected Properties getProperties() {
    return MultiStorageEnv.getProperties(OTHER_NAMESPACE);
  }

  @Test
  void execute_namespaceInJdbcStorageNotMappedGiven_shouldThrowScalarDlCleanupException(
      @TempDir Path checkpointDir) throws Exception {
    // Arrange
    // Create a transactional table in another namespace in the JDBC storage, which the
    // configuration of the run does not map.
    String unmappedNamespace = OTHER_NAMESPACE + "_unmapped";
    String table = "test_table";
    Properties setupProps = getProperties();
    setupProps.setProperty(
        MultiStorageConfig.NAMESPACE_MAPPING,
        OTHER_NAMESPACE
            + ":"
            + MultiStorageEnv.JDBC_STORAGE
            + ","
            + unmappedNamespace
            + ":"
            + MultiStorageEnv.JDBC_STORAGE);
    DistributedTransactionAdmin setupAdmin =
        TransactionFactory.create(setupProps).getTransactionAdmin();
    try {
      setupAdmin.createNamespace(unmappedNamespace, true);
      setupAdmin.createTable(
          unmappedNamespace,
          table,
          TableMetadata.newBuilder()
              .addColumn("pk", DataType.TEXT)
              .addColumn("val", DataType.TEXT)
              .addPartitionKey("pk")
              .build(),
          true);

      // Act Assert
      assertExecuteFails(
          getProperties(),
          checkpointDir,
          ScalarDlCleanupError.MULTI_STORAGE_UNREACHABLE_NAMESPACES_FOUND,
          unmappedNamespace);
    } finally {
      try {
        setupAdmin.dropTable(unmappedNamespace, table, true);
        setupAdmin.dropNamespace(unmappedNamespace, true);
      } finally {
        setupAdmin.close();
      }
    }
  }

  @Test
  void execute_mappedNamespaceNotInJdbcStorageGiven_shouldThrowScalarDlCleanupException(
      @TempDir Path checkpointDir) {
    // Arrange
    String missingNamespace = OTHER_NAMESPACE + "_missing";
    Properties props = getProperties();
    props.setProperty(
        MultiStorageConfig.NAMESPACE_MAPPING,
        OTHER_NAMESPACE
            + ":"
            + MultiStorageEnv.JDBC_STORAGE
            + ","
            + missingNamespace
            + ":"
            + MultiStorageEnv.JDBC_STORAGE);

    // Act Assert
    assertExecuteFails(
        props,
        checkpointDir,
        ScalarDlCleanupError.MULTI_STORAGE_MAPPED_NAMESPACE_NOT_FOUND,
        missingNamespace);
  }

  @Test
  void execute_wrongMetadataSchemaOfJdbcStorageGiven_shouldThrowScalarDlCleanupException(
      @TempDir Path checkpointDir) {
    // Arrange
    // The schema of OTHER_NAMESPACE exists in the JDBC database, but the metadata schema that the
    // configuration points to has no metadata of it, so its tables would not be found.
    Properties props = getProperties();
    props.setProperty(
        MultiStorageConfig.STORAGES
            + "."
            + MultiStorageEnv.JDBC_STORAGE
            + "."
            + JdbcConfig.TABLE_METADATA_SCHEMA.substring("scalar.db.".length()),
        "it_ledger_orch_wrong_metadata");

    // Act Assert
    assertExecuteFails(
        props,
        checkpointDir,
        ScalarDlCleanupError.MULTI_STORAGE_MAPPED_NAMESPACE_NOT_FOUND,
        OTHER_NAMESPACE);
  }

  /**
   * Asserts that a new run with the given configuration fails with the given error. It creates the
   * orchestrator in the same way as the command, so that the validation runs against the admins
   * that the configuration produces.
   */
  private static void assertExecuteFails(
      Properties props, Path checkpointDir, ScalarDlCleanupError error, String namespace) {
    try (LedgerFinalizeOrchestrator orchestrator =
        LedgerFinalizeOrchestrator.create(props, checkpointDir)) {
      assertThatThrownBy(orchestrator::execute)
          .isInstanceOf(ScalarDlCleanupException.class)
          .hasMessageContaining(error.buildCode())
          .hasMessageContaining(namespace);
    }
  }
}
