package com.scalar.dl.tools.cleanup.multistorage;

import com.scalar.dl.tools.cleanup.LedgerFinalizeOrchestratorIntegrationTestBase;
import java.util.Properties;

/**
 * Runs the tests on multi-storage with Cosmos DB and a JDBC database. The Coordinator table and
 * ScalarDL's tables are in Cosmos DB, while the tables in {@code OTHER_NAMESPACE} are in the JDBC
 * database.
 */
class MultiStorageLedgerFinalizeOrchestratorIntegrationTest
    extends LedgerFinalizeOrchestratorIntegrationTestBase {

  @Override
  protected Properties getProperties() {
    return MultiStorageEnv.getProperties(OTHER_NAMESPACE);
  }
}
