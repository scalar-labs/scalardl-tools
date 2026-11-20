package com.scalar.dl.tools.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.scalar.db.api.DistributedStorageAdmin;
import com.scalar.db.api.TableMetadata;
import com.scalar.db.io.DataType;
import com.scalar.db.transaction.consensuscommit.ConsensusCommitUtils;
import com.scalar.db.transaction.consensuscommit.CoordinatorStateAccessor;
import com.scalar.dl.tools.common.ScalarDlCleanupError;
import com.scalar.dl.tools.common.ScalarDlCleanupException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TransactionalTableDiscoveryTest {

  private static final TableMetadata USER_TABLE_METADATA =
      TableMetadata.newBuilder()
          .addColumn("pk", DataType.TEXT)
          .addColumn("col", DataType.INT)
          .addPartitionKey("pk")
          .build();
  private static final TableMetadata TRANSACTION_TABLE_METADATA =
      ConsensusCommitUtils.buildTransactionTableMetadata(USER_TABLE_METADATA);

  private DistributedStorageAdmin admin;

  @BeforeEach
  void setUp() throws Exception {
    admin = mock(DistributedStorageAdmin.class);
    when(admin.getTableMetadata("ns1", "asset")).thenReturn(TRANSACTION_TABLE_METADATA);
    when(admin.getTableMetadata("ns1", "asset_metadata")).thenReturn(TRANSACTION_TABLE_METADATA);
    when(admin.getTableMetadata("ns1", "contract")).thenReturn(USER_TABLE_METADATA);
  }

  private void givenTables(String namespace, String... tables) throws Exception {
    when(admin.getNamespaceTableNames(namespace))
        .thenReturn(new LinkedHashSet<>(Arrays.asList(tables)));
  }

  @Test
  void discover_mixOfTransactionalAndNonTransactionalTablesGiven_shouldReturnOnlyTransactional()
      throws Exception {
    // Arrange
    when(admin.getNamespaceNames()).thenReturn(new LinkedHashSet<>(Arrays.asList("ns1")));
    givenTables("ns1", "asset", "asset_metadata", "contract");
    TransactionalTableDiscovery discovery =
        new TransactionalTableDiscovery(admin, CoordinatorStateAccessor.NAMESPACE);

    // Act
    List<String> tables = discovery.discover();

    // Assert
    assertThat(tables).containsExactlyInAnyOrder("ns1.asset", "ns1.asset_metadata");
  }

  @Test
  void discover_coordinatorNamespaceOverriddenGiven_shouldExcludeThatCoordinatorTable()
      throws Exception {
    // Arrange
    when(admin.getNamespaceNames())
        .thenReturn(new LinkedHashSet<>(Arrays.asList("ns1", "custom_coordinator", "coordinator")));
    givenTables("ns1", "asset", "asset_metadata");
    givenTables("custom_coordinator", CoordinatorStateAccessor.TABLE);
    // A table named "state" in the default coordinator namespace is not the configured coordinator
    // table here, so it is a target.
    givenTables("coordinator", CoordinatorStateAccessor.TABLE);
    when(admin.getTableMetadata("custom_coordinator", CoordinatorStateAccessor.TABLE))
        .thenReturn(TRANSACTION_TABLE_METADATA);
    when(admin.getTableMetadata("coordinator", CoordinatorStateAccessor.TABLE))
        .thenReturn(TRANSACTION_TABLE_METADATA);
    TransactionalTableDiscovery discovery =
        new TransactionalTableDiscovery(admin, "custom_coordinator");

    // Act
    List<String> tables = discovery.discover();

    // Assert
    assertThat(tables)
        .containsExactlyInAnyOrder("ns1.asset", "ns1.asset_metadata", "coordinator.state");
  }

  @Test
  void discover_onlyOneTargetTableGiven_shouldThrowTooFewTargetTables() throws Exception {
    // Arrange
    when(admin.getNamespaceNames()).thenReturn(new LinkedHashSet<>(Arrays.asList("ns1")));
    givenTables("ns1", "asset", "contract");
    TransactionalTableDiscovery discovery =
        new TransactionalTableDiscovery(admin, CoordinatorStateAccessor.NAMESPACE);

    // Act & Assert
    assertThatThrownBy(discovery::discover)
        .isInstanceOf(ScalarDlCleanupException.class)
        .hasMessage(ScalarDlCleanupError.TOO_FEW_TARGET_TABLES.buildMessage(1));
  }
}
