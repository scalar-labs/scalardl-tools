package com.scalar.dl.tools.cleanup.cosmos;

import com.scalar.dl.tools.cleanup.LedgerFinalizeOrchestratorIntegrationTestBase;
import java.util.Properties;

class CosmosLedgerFinalizeOrchestratorIntegrationTest
    extends LedgerFinalizeOrchestratorIntegrationTestBase {

  @Override
  protected Properties getProperties() {
    return CosmosEnv.getProperties();
  }
}
