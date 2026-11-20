package com.scalar.dl.tools.cleanup.cosmos;

import com.scalar.dl.tools.cleanup.AuditorVerifyOrchestratorIntegrationTestBase;
import java.util.Properties;

class CosmosAuditorVerifyOrchestratorIntegrationTest
    extends AuditorVerifyOrchestratorIntegrationTestBase {

  @Override
  protected Properties getProperties() {
    return CosmosEnv.getProperties();
  }
}
