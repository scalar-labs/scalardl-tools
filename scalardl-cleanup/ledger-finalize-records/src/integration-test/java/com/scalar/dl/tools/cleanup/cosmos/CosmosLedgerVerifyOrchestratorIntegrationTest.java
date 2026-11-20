package com.scalar.dl.tools.cleanup.cosmos;

import com.scalar.dl.tools.cleanup.LedgerVerifyOrchestratorIntegrationTestBase;
import java.util.Properties;

class CosmosLedgerVerifyOrchestratorIntegrationTest
    extends LedgerVerifyOrchestratorIntegrationTestBase {

  @Override
  protected Properties getProperties() {
    return CosmosEnv.getProperties();
  }
}
