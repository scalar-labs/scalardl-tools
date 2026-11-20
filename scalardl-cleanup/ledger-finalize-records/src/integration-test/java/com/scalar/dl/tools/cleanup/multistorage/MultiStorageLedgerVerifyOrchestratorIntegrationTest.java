package com.scalar.dl.tools.cleanup.multistorage;

import com.scalar.dl.tools.cleanup.LedgerVerifyOrchestratorIntegrationTestBase;
import java.util.Properties;

/**
 * Runs the tests on multi-storage with Cosmos DB and a JDBC database. The Coordinator table and
 * ScalarDL's tables are in Cosmos DB, while the tables in {@code OTHER_NAMESPACE} are in the JDBC
 * database, so the test with a non-terminal record only in that namespace covers the detection in
 * the JDBC database.
 */
class MultiStorageLedgerVerifyOrchestratorIntegrationTest
    extends LedgerVerifyOrchestratorIntegrationTestBase {

  @Override
  protected Properties getProperties() {
    return MultiStorageEnv.getProperties(OTHER_NAMESPACE);
  }
}
