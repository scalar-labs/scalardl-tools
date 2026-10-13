package com.scalar.dl.tools.cleanup.jdbc;

import com.scalar.dl.tools.cleanup.CoordinatorCleanupOrchestratorIntegrationTestBase;
import java.util.Properties;

class JdbcCoordinatorCleanupOrchestratorIntegrationTest
    extends CoordinatorCleanupOrchestratorIntegrationTestBase {

  @Override
  protected Properties getProperties() {
    return JdbcEnv.getProperties();
  }
}
