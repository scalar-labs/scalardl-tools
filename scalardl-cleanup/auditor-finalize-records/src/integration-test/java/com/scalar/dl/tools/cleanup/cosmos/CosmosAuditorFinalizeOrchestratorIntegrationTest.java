package com.scalar.dl.tools.cleanup.cosmos;

import com.scalar.dl.tools.cleanup.AuditorFinalizeOrchestratorIntegrationTestBase;
import java.util.Properties;

class CosmosAuditorFinalizeOrchestratorIntegrationTest
    extends AuditorFinalizeOrchestratorIntegrationTestBase {

  @Override
  protected Properties getProperties() {
    return CosmosEnv.getProperties();
  }
}
