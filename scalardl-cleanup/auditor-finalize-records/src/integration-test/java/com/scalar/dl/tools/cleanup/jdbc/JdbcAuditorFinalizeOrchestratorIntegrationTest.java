package com.scalar.dl.tools.cleanup.jdbc;

import com.scalar.dl.tools.cleanup.AuditorFinalizeOrchestratorIntegrationTestBase;
import java.util.Properties;

class JdbcAuditorFinalizeOrchestratorIntegrationTest
    extends AuditorFinalizeOrchestratorIntegrationTestBase {

  @Override
  protected Properties getProperties() {
    return JdbcEnv.getProperties();
  }
}
