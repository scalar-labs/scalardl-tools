package com.scalar.dl.tools.cleanup.jdbc;

import com.scalar.dl.tools.cleanup.LedgerFinalizeOrchestratorIntegrationTestBase;
import java.util.Properties;

class JdbcLedgerFinalizeOrchestratorIntegrationTest
    extends LedgerFinalizeOrchestratorIntegrationTestBase {

  @Override
  protected Properties getProperties() {
    return JdbcEnv.getProperties();
  }
}
