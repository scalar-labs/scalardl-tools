package com.scalar.dl.tools.cleanup.cosmos;

import com.scalar.dl.tools.cleanup.RequestProofCleanupOrchestratorIntegrationTestBase;
import java.util.Properties;

class CosmosRequestProofCleanupOrchestratorIntegrationTest
    extends RequestProofCleanupOrchestratorIntegrationTestBase {

  @Override
  protected Properties getProperties() {
    return CosmosEnv.getProperties();
  }
}
