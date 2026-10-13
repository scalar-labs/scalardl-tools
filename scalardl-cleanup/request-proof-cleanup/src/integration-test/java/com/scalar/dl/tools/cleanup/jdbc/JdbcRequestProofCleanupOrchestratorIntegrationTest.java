package com.scalar.dl.tools.cleanup.jdbc;

import com.scalar.dl.tools.cleanup.RequestProofCleanupOrchestratorIntegrationTestBase;
import java.util.Properties;

class JdbcRequestProofCleanupOrchestratorIntegrationTest
    extends RequestProofCleanupOrchestratorIntegrationTestBase {

  @Override
  protected Properties getProperties() {
    return JdbcEnv.getProperties();
  }
}
