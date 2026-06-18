package com.scalar.dl.tools.cleanup.cosmos;

import com.scalar.dl.tools.cleanup.CoordinatorCleanupOrchestratorIntegrationTestBase;
import java.util.Properties;

class CosmosCoordinatorCleanupOrchestratorIntegrationTest
    extends CoordinatorCleanupOrchestratorIntegrationTestBase {

  @Override
  protected Properties getProperties() {
    return CosmosEnv.getProperties();
  }
}
