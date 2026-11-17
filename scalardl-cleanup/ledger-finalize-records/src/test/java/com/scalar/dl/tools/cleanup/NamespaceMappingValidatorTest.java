package com.scalar.dl.tools.cleanup;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.scalar.db.api.DistributedStorageAdmin;
import com.scalar.db.api.TableMetadata;
import com.scalar.db.io.DataType;
import com.scalar.db.transaction.consensuscommit.ConsensusCommitUtils;
import com.scalar.dl.tools.common.Category;
import com.scalar.dl.tools.common.ScalarDlCleanupError;
import com.scalar.dl.tools.common.ScalarDlCleanupException;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NamespaceMappingValidatorTest {

  private static final String COSMOS = "cosmos";
  private static final String POSTGRESQL = "postgresql";

  private static final TableMetadata USER_TABLE_METADATA =
      TableMetadata.newBuilder()
          .addColumn("pk", DataType.TEXT)
          .addColumn("col", DataType.INT)
          .addPartitionKey("pk")
          .build();
  private static final TableMetadata TRANSACTION_TABLE_METADATA =
      ConsensusCommitUtils.buildTransactionTableMetadata(USER_TABLE_METADATA);

  private DistributedStorageAdmin cosmosAdmin;
  private DistributedStorageAdmin postgresqlAdmin;

  @BeforeEach
  void setUp() throws Exception {
    cosmosAdmin = mock(DistributedStorageAdmin.class);
    postgresqlAdmin = mock(DistributedStorageAdmin.class);
    // Every table is transactional unless a test says otherwise.
    when(cosmosAdmin.getTableMetadata(anyString(), anyString()))
        .thenReturn(TRANSACTION_TABLE_METADATA);
    when(postgresqlAdmin.getTableMetadata(anyString(), anyString()))
        .thenReturn(TRANSACTION_TABLE_METADATA);
  }

  private NamespaceMappingValidator newValidator(Map<String, String> namespaceStorageMap) {
    return new NamespaceMappingValidator(
        ImmutableMap.of(COSMOS, cosmosAdmin, POSTGRESQL, postgresqlAdmin),
        namespaceStorageMap,
        COSMOS);
  }

  @Test
  void validate_everyNamespaceResolvedToItsStorageGiven_shouldNotThrow() throws Exception {
    // Arrange
    when(cosmosAdmin.getNamespaceNames()).thenReturn(ImmutableSet.of("scalar", "coordinator"));
    when(cosmosAdmin.getNamespaceTableNames("scalar")).thenReturn(ImmutableSet.of("asset"));
    when(postgresqlAdmin.getNamespaceNames()).thenReturn(ImmutableSet.of("ns1", "ns2"));
    when(postgresqlAdmin.getNamespaceTableNames(anyString())).thenReturn(ImmutableSet.of("tbl"));

    NamespaceMappingValidator validator =
        newValidator(ImmutableMap.of("ns1", POSTGRESQL, "ns2", POSTGRESQL));

    // Act Assert
    assertThatCode(validator::validate).doesNotThrowAnyException();
    // The namespaces that resolve to the storage that has them need no further check.
    verify(cosmosAdmin, never()).getNamespaceTableNames(anyString());
    verify(postgresqlAdmin, never()).getNamespaceTableNames(anyString());
  }

  @Test
  void validate_mappedNamespaceNotInMetadataGiven_shouldThrowScalarDlCleanupException()
      throws Exception {
    // Arrange
    when(postgresqlAdmin.getNamespaceNames()).thenReturn(ImmutableSet.of("ns1"));

    NamespaceMappingValidator validator = newValidator(ImmutableMap.of("<namespace>", POSTGRESQL));

    // Act Assert
    assertThatThrownBy(validator::validate)
        .isInstanceOf(ScalarDlCleanupException.class)
        .hasMessageContaining(
            ScalarDlCleanupError.MULTI_STORAGE_MAPPED_NAMESPACE_NOT_FOUND.buildCode())
        .hasMessageContaining("<namespace>")
        .hasMessageContaining(POSTGRESQL)
        .extracting(e -> ((ScalarDlCleanupException) e).getCategory())
        .isEqualTo(Category.USER_ERROR);
  }

  @Test
  void validate_mappedNamespaceWithoutTablesInMetadataGiven_shouldThrowScalarDlCleanupException()
      throws Exception {
    // Arrange
    // The metadata of a storage lists only the namespaces that have tables. With a wrong metadata
    // schema of a JDBC storage, or for a namespace without tables, the metadata does not have the
    // namespace even though it exists, and the table discovery would not find any table in it.
    when(postgresqlAdmin.getNamespaceNames()).thenReturn(ImmutableSet.of("scalardb"));

    NamespaceMappingValidator validator = newValidator(ImmutableMap.of("ns1", POSTGRESQL));

    // Act Assert
    assertThatThrownBy(validator::validate)
        .isInstanceOf(ScalarDlCleanupException.class)
        .hasMessageContaining(
            ScalarDlCleanupError.MULTI_STORAGE_MAPPED_NAMESPACE_NOT_FOUND.buildCode())
        .hasMessageContaining("ns1");
  }

  @Test
  void validate_namespaceMappedToDefaultStorageNotFoundGiven_shouldThrowScalarDlCleanupException()
      throws Exception {
    // Arrange
    when(cosmosAdmin.getNamespaceNames()).thenReturn(ImmutableSet.of("scalar"));

    NamespaceMappingValidator validator = newValidator(ImmutableMap.of("ns1", COSMOS));

    // Act Assert
    assertThatThrownBy(validator::validate)
        .isInstanceOf(ScalarDlCleanupException.class)
        .hasMessageContaining(
            ScalarDlCleanupError.MULTI_STORAGE_MAPPED_NAMESPACE_NOT_FOUND.buildCode())
        .hasMessageContaining(COSMOS);
  }

  @Test
  void validate_unmappedNamespacesInNonDefaultStorageGiven_shouldThrowScalarDlCleanupException()
      throws Exception {
    // Arrange
    when(postgresqlAdmin.getNamespaceNames()).thenReturn(ImmutableSet.of("ns1", "ns3", "ns2"));
    when(postgresqlAdmin.getNamespaceTableNames("ns2")).thenReturn(ImmutableSet.of("tbl"));
    when(postgresqlAdmin.getNamespaceTableNames("ns3")).thenReturn(ImmutableSet.of("tbl"));

    NamespaceMappingValidator validator = newValidator(ImmutableMap.of("ns1", POSTGRESQL));

    // Act Assert
    assertThatThrownBy(validator::validate)
        .isInstanceOf(ScalarDlCleanupException.class)
        .hasMessageContaining(
            ScalarDlCleanupError.MULTI_STORAGE_UNREACHABLE_NAMESPACES_FOUND.buildCode())
        .hasMessageContaining(POSTGRESQL)
        .hasMessageContaining("[ns2, ns3]")
        .extracting(e -> ((ScalarDlCleanupException) e).getCategory())
        .isEqualTo(Category.USER_ERROR);
  }

  @Test
  void validate_storageWithoutAnyMappingGiven_shouldStillDetectUnmappedNamespaces()
      throws Exception {
    // Arrange
    when(postgresqlAdmin.getNamespaceNames()).thenReturn(ImmutableSet.of("ns1"));
    when(postgresqlAdmin.getNamespaceTableNames("ns1")).thenReturn(ImmutableSet.of("tbl"));

    NamespaceMappingValidator validator = newValidator(Collections.emptyMap());

    // Act Assert
    assertThatThrownBy(validator::validate)
        .isInstanceOf(ScalarDlCleanupException.class)
        .hasMessageContaining(
            ScalarDlCleanupError.MULTI_STORAGE_UNREACHABLE_NAMESPACES_FOUND.buildCode())
        .hasMessageContaining("[ns1]");
  }

  @Test
  void validate_defaultStorageNamespaceMappedToAnotherStorageGiven_shouldThrow() throws Exception {
    // Arrange
    // ns1 has transactional tables in the default storage but is mapped to PostgreSQL, where it
    // also exists, so its tables in the default storage are not reached.
    when(cosmosAdmin.getNamespaceNames()).thenReturn(ImmutableSet.of("scalar", "ns1"));
    when(cosmosAdmin.getNamespaceTableNames("ns1")).thenReturn(ImmutableSet.of("tbl"));
    when(postgresqlAdmin.getNamespaceNames()).thenReturn(ImmutableSet.of("ns1"));

    NamespaceMappingValidator validator = newValidator(ImmutableMap.of("ns1", POSTGRESQL));

    // Act Assert
    assertThatThrownBy(validator::validate)
        .isInstanceOf(ScalarDlCleanupException.class)
        .hasMessageContaining(
            ScalarDlCleanupError.MULTI_STORAGE_UNREACHABLE_NAMESPACES_FOUND.buildCode())
        .hasMessageContaining(COSMOS)
        .hasMessageContaining("[ns1]");
  }

  @Test
  void validate_nonDefaultStorageNamespaceMappedToDefaultStorageGiven_shouldThrow()
      throws Exception {
    // Arrange
    // ns1 exists in both storages but is mapped to the default one, so its tables in PostgreSQL
    // are not reached.
    when(cosmosAdmin.getNamespaceNames()).thenReturn(ImmutableSet.of("ns1"));
    when(postgresqlAdmin.getNamespaceNames()).thenReturn(ImmutableSet.of("ns1"));
    when(postgresqlAdmin.getNamespaceTableNames("ns1")).thenReturn(ImmutableSet.of("tbl"));

    NamespaceMappingValidator validator = newValidator(ImmutableMap.of("ns1", COSMOS));

    // Act Assert
    assertThatThrownBy(validator::validate)
        .isInstanceOf(ScalarDlCleanupException.class)
        .hasMessageContaining(
            ScalarDlCleanupError.MULTI_STORAGE_UNREACHABLE_NAMESPACES_FOUND.buildCode())
        .hasMessageContaining(POSTGRESQL)
        .hasMessageContaining("[ns1]");
  }

  @Test
  void validate_unreachableNamespacesWithoutTransactionTablesGiven_shouldNotThrow()
      throws Exception {
    // Arrange
    // "scalardb" stands for the metadata namespace, which has no ScalarDB tables. "ns2" has a
    // non-transactional table only, and "ns3" has a table without metadata.
    when(postgresqlAdmin.getNamespaceNames())
        .thenReturn(ImmutableSet.of("ns1", "scalardb", "ns2", "ns3"));
    when(postgresqlAdmin.getNamespaceTableNames("scalardb")).thenReturn(Collections.emptySet());
    when(postgresqlAdmin.getNamespaceTableNames("ns2")).thenReturn(ImmutableSet.of("tbl"));
    when(postgresqlAdmin.getTableMetadata("ns2", "tbl")).thenReturn(USER_TABLE_METADATA);
    when(postgresqlAdmin.getNamespaceTableNames("ns3")).thenReturn(ImmutableSet.of("tbl"));
    when(postgresqlAdmin.getTableMetadata("ns3", "tbl")).thenReturn(null);

    NamespaceMappingValidator validator = newValidator(ImmutableMap.of("ns1", POSTGRESQL));

    // Act Assert
    assertThatCode(validator::validate).doesNotThrowAnyException();
  }

  @Test
  void validate_mappingToUnknownStorageGiven_shouldThrowIllegalStateException() {
    // Arrange
    NamespaceMappingValidator validator = newValidator(ImmutableMap.of("ns1", "unknown"));

    // Act Assert
    assertThatThrownBy(validator::validate).isInstanceOf(IllegalStateException.class);
  }
}
