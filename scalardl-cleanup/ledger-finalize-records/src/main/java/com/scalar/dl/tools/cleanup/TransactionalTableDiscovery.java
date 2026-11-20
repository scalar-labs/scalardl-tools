package com.scalar.dl.tools.cleanup;

import com.scalar.db.api.DistributedStorageAdmin;
import com.scalar.db.api.TableMetadata;
import com.scalar.db.config.DatabaseConfig;
import com.scalar.db.exception.storage.ExecutionException;
import com.scalar.db.transaction.consensuscommit.ConsensusCommitConfig;
import com.scalar.db.transaction.consensuscommit.ConsensusCommitUtils;
import com.scalar.db.transaction.consensuscommit.CoordinatorStateAccessor;
import com.scalar.db.util.ScalarDbUtils;
import com.scalar.dl.tools.common.ScalarDlCleanupError;
import com.scalar.dl.tools.common.ScalarDlCleanupException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Discovers the transactional tables that hold the records to finalize: every table managed by the
 * Consensus Commit transaction manager, except the coordinator table.
 */
final class TransactionalTableDiscovery {

  /** The asset and asset_metadata tables the Ledger schema always has. */
  private static final int MINIMUM_TARGET_TABLE_COUNT = 2;

  private static final Logger logger = LoggerFactory.getLogger(TransactionalTableDiscovery.class);

  private final DistributedStorageAdmin admin;
  private final String coordinatorNamespace;

  TransactionalTableDiscovery(DistributedStorageAdmin admin, String coordinatorNamespace) {
    this.admin = admin;
    this.coordinatorNamespace = coordinatorNamespace;
  }

  /** Resolves the namespace of the coordinator table. */
  static String resolveCoordinatorNamespace(DatabaseConfig dbConfig) {
    return new ConsensusCommitConfig(dbConfig)
        .getCoordinatorNamespace()
        .orElse(CoordinatorStateAccessor.NAMESPACE);
  }

  /**
   * Returns the qualified names ({@code namespace.table}) of the target tables.
   *
   * @throws ScalarDlCleanupException if fewer than the tables a Ledger schema always has were found
   */
  List<String> discover() throws ExecutionException {
    List<String> tables = discoverTables();
    // A loaded Ledger schema always has the asset and asset_metadata tables, both transactional.
    if (tables.size() < MINIMUM_TARGET_TABLE_COUNT) {
      throw new ScalarDlCleanupException(ScalarDlCleanupError.TOO_FEW_TARGET_TABLES, tables.size());
    }
    return tables;
  }

  private List<String> discoverTables() throws ExecutionException {
    String coordinatorTable =
        ScalarDbUtils.getFullTableName(coordinatorNamespace, CoordinatorStateAccessor.TABLE);
    List<String> tables = new ArrayList<>();
    for (String namespace : admin.getNamespaceNames()) {
      for (String table : admin.getNamespaceTableNames(namespace)) {
        String qualifiedTable = ScalarDbUtils.getFullTableName(namespace, table);
        if (qualifiedTable.equals(coordinatorTable)) {
          continue;
        }
        if (!isTransactionTable(namespace, table)) {
          logger.info("Skipping the table because it is not transactional: {}", qualifiedTable);
          continue;
        }
        tables.add(qualifiedTable);
      }
    }
    return tables;
  }

  /**
   * Returns whether the table is managed by the Consensus Commit transaction manager.
   *
   * @throws IllegalStateException if the table has no metadata even though it was just listed.
   */
  private boolean isTransactionTable(String namespace, String table) throws ExecutionException {
    TableMetadata metadata = admin.getTableMetadata(namespace, table);
    if (metadata == null) {
      throw new IllegalStateException(
          "Table metadata not found for " + ScalarDbUtils.getFullTableName(namespace, table));
    }
    return ConsensusCommitUtils.isTransactionTableMetadata(metadata);
  }
}
