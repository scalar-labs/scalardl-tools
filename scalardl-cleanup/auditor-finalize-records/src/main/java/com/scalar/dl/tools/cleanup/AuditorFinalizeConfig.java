package com.scalar.dl.tools.cleanup;

import com.google.common.annotations.VisibleForTesting;
import com.scalar.dl.client.config.ClientConfig;
import com.scalar.dl.ledger.config.ConfigUtils;
import com.scalar.dl.ledger.config.GrpcClientConfig;
import com.scalar.dl.ledger.config.TargetConfig;
import com.scalar.dl.tools.common.AuditorInternalValues;
import java.util.Properties;

/** Auditor-tool-specific settings for {@code auditor-finalize-records}. */
public final class AuditorFinalizeConfig {

  // Mirror ClientConfig.DEFAULT_* since they are private.
  @VisibleForTesting static final long DEFAULT_GRPC_DEADLINE_MS = 60000;
  @VisibleForTesting static final String DEFAULT_AUDITOR_HOST = "localhost";
  @VisibleForTesting static final int DEFAULT_AUDITOR_PORT = 40051;
  @VisibleForTesting static final int DEFAULT_AUDITOR_PRIVILEGED_PORT = 40052;
  @VisibleForTesting static final boolean DEFAULT_AUDITOR_TLS_ENABLED = false;

  private final String baseNamespace;
  private final TargetConfig auditorTargetConfig;

  public AuditorFinalizeConfig(Properties props) {
    this.baseNamespace =
        props.getProperty(
            AuditorInternalValues.AUDITOR_NAMESPACE_PROPERTY,
            AuditorInternalValues.DEFAULT_BASE_NAMESPACE);
    this.auditorTargetConfig = buildAuditorTargetConfig(props);
  }

  @VisibleForTesting
  static TargetConfig buildAuditorTargetConfig(Properties props) {
    // The CA root cert is taken from the inline PEM if set, otherwise read from the cert file path.
    String caRootCertPem =
        ConfigUtils.getString(props, ClientConfig.AUDITOR_TLS_CA_ROOT_CERT_PEM, null);
    String caRootCert =
        caRootCertPem != null
            ? caRootCertPem
            : ConfigUtils.getStringFromFilePath(
                props, ClientConfig.AUDITOR_TLS_CA_ROOT_CERT_PATH, null);

    return TargetConfig.newBuilder()
        .host(ConfigUtils.getString(props, ClientConfig.AUDITOR_HOST, DEFAULT_AUDITOR_HOST))
        .port(ConfigUtils.getInt(props, ClientConfig.AUDITOR_PORT, DEFAULT_AUDITOR_PORT))
        .privilegedPort(
            ConfigUtils.getInt(
                props, ClientConfig.AUDITOR_PRIVILEGED_PORT, DEFAULT_AUDITOR_PRIVILEGED_PORT))
        .tlsEnabled(
            ConfigUtils.getBoolean(
                props, ClientConfig.AUDITOR_TLS_ENABLED, DEFAULT_AUDITOR_TLS_ENABLED))
        .tlsCaRootCert(caRootCert)
        .tlsOverrideAuthority(
            ConfigUtils.getString(props, ClientConfig.AUDITOR_TLS_OVERRIDE_AUTHORITY, null))
        .authorizationCredential(
            ConfigUtils.getString(props, ClientConfig.AUDITOR_AUTHORIZATION_CREDENTIAL, null))
        .grpcClientConfig(
            GrpcClientConfig.newBuilder().deadlineDurationMillis(DEFAULT_GRPC_DEADLINE_MS).build())
        .build();
  }

  /** Get the Auditor base namespace, default {@code auditor}. */
  public String getBaseNamespace() {
    return baseNamespace;
  }

  /** Get the config used to reach the Auditor server. */
  public TargetConfig getAuditorTargetConfig() {
    return auditorTargetConfig;
  }
}
