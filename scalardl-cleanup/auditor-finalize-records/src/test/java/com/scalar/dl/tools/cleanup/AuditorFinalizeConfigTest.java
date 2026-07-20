package com.scalar.dl.tools.cleanup;

import static org.assertj.core.api.Assertions.assertThat;

import com.scalar.dl.client.config.ClientConfig;
import com.scalar.dl.ledger.config.TargetConfig;
import com.scalar.dl.tools.common.AuditorInternalValues;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuditorFinalizeConfigTest {

  @TempDir Path tempDir;

  @Test
  void buildAuditorTargetConfig_nothingGiven_shouldUseDefaults() {
    // Arrange
    Properties props = new Properties();

    // Act
    TargetConfig config = AuditorFinalizeConfig.buildAuditorTargetConfig(props);

    // Assert
    assertThat(config.getTargetHost()).isEqualTo(AuditorFinalizeConfig.DEFAULT_AUDITOR_HOST);
    assertThat(config.getTargetPort()).isEqualTo(AuditorFinalizeConfig.DEFAULT_AUDITOR_PORT);
    assertThat(config.getTargetPrivilegedPort())
        .isEqualTo(AuditorFinalizeConfig.DEFAULT_AUDITOR_PRIVILEGED_PORT);
    assertThat(config.isTargetTlsEnabled())
        .isEqualTo(AuditorFinalizeConfig.DEFAULT_AUDITOR_TLS_ENABLED);
    assertThat(config.getTargetTlsCaRootCert()).isNull();
    assertThat(config.getTargetTlsOverrideAuthority()).isNull();
    assertThat(config.getGrpcClientConfig().getDeadlineDurationMillis())
        .isEqualTo(AuditorFinalizeConfig.DEFAULT_GRPC_DEADLINE_MS);
  }

  @Test
  void buildAuditorTargetConfig_hostGiven_shouldUseIt() {
    // Arrange
    Properties props = new Properties();
    props.setProperty(ClientConfig.AUDITOR_HOST, "auditor.example.com");

    // Act
    TargetConfig config = AuditorFinalizeConfig.buildAuditorTargetConfig(props);

    // Assert
    assertThat(config.getTargetHost()).isEqualTo("auditor.example.com");
  }

  @Test
  void buildAuditorTargetConfig_portsGiven_shouldUseThem() {
    // Arrange
    Properties props = new Properties();
    props.setProperty(ClientConfig.AUDITOR_PORT, "50051");
    props.setProperty(ClientConfig.AUDITOR_PRIVILEGED_PORT, "50052");

    // Act
    TargetConfig config = AuditorFinalizeConfig.buildAuditorTargetConfig(props);

    // Assert
    assertThat(config.getTargetPort()).isEqualTo(50051);
    assertThat(config.getTargetPrivilegedPort()).isEqualTo(50052);
  }

  @Test
  void buildAuditorTargetConfig_tlsEnabledGiven_shouldUseIt() {
    // Arrange
    Properties props = new Properties();
    props.setProperty(ClientConfig.AUDITOR_TLS_ENABLED, "true");

    // Act
    TargetConfig config = AuditorFinalizeConfig.buildAuditorTargetConfig(props);

    // Assert
    assertThat(config.isTargetTlsEnabled()).isTrue();
  }

  @Test
  void buildAuditorTargetConfig_tlsCaRootCertPemGiven_shouldUseIt() {
    // Arrange
    Properties props = new Properties();
    props.setProperty(ClientConfig.AUDITOR_TLS_CA_ROOT_CERT_PEM, "cert-from-pem");

    // Act
    TargetConfig config = AuditorFinalizeConfig.buildAuditorTargetConfig(props);

    // Assert
    assertThat(config.getTargetTlsCaRootCert()).isEqualTo("cert-from-pem");
  }

  @Test
  void buildAuditorTargetConfig_tlsCaRootCertPathGiven_shouldReadCertFromFile() throws IOException {
    // Arrange
    Path certFile = tempDir.resolve("ca.pem");
    Files.write(certFile, "cert-from-file".getBytes(StandardCharsets.UTF_8));
    Properties props = new Properties();
    props.setProperty(ClientConfig.AUDITOR_TLS_CA_ROOT_CERT_PATH, certFile.toString());

    // Act
    TargetConfig config = AuditorFinalizeConfig.buildAuditorTargetConfig(props);

    // Assert
    assertThat(config.getTargetTlsCaRootCert()).isEqualTo("cert-from-file");
  }

  @Test
  void buildAuditorTargetConfig_tlsCaRootCertPemAndPathGiven_shouldPreferPem() throws IOException {
    // Arrange
    Path certFile = tempDir.resolve("ca.pem");
    Files.write(certFile, "cert-from-file".getBytes(StandardCharsets.UTF_8));
    Properties props = new Properties();
    props.setProperty(ClientConfig.AUDITOR_TLS_CA_ROOT_CERT_PEM, "cert-from-pem");
    props.setProperty(ClientConfig.AUDITOR_TLS_CA_ROOT_CERT_PATH, certFile.toString());

    // Act
    TargetConfig config = AuditorFinalizeConfig.buildAuditorTargetConfig(props);

    // Assert
    assertThat(config.getTargetTlsCaRootCert()).isEqualTo("cert-from-pem");
  }

  @Test
  void buildAuditorTargetConfig_tlsOverrideAuthorityGiven_shouldUseIt() {
    // Arrange
    Properties props = new Properties();
    props.setProperty(ClientConfig.AUDITOR_TLS_OVERRIDE_AUTHORITY, "auditor.test");

    // Act
    TargetConfig config = AuditorFinalizeConfig.buildAuditorTargetConfig(props);

    // Assert
    assertThat(config.getTargetTlsOverrideAuthority()).isEqualTo("auditor.test");
  }

  @Test
  void buildAuditorTargetConfig_grpcDeadlineSetInProps_shouldStillUseFixedDeadline() {
    // Arrange
    Properties props = new Properties();
    props.setProperty(ClientConfig.GRPC_DEADLINE_DURATION_MILLIS, "12345");

    // Act
    TargetConfig config = AuditorFinalizeConfig.buildAuditorTargetConfig(props);

    // Assert
    assertThat(config.getGrpcClientConfig().getDeadlineDurationMillis())
        .isEqualTo(AuditorFinalizeConfig.DEFAULT_GRPC_DEADLINE_MS);
  }

  @Test
  void getBaseNamespace_notSetGiven_shouldReturnDefault() {
    // Arrange
    Properties props = new Properties();

    // Act
    AuditorFinalizeConfig config = new AuditorFinalizeConfig(props);

    // Assert
    assertThat(config.getBaseNamespace()).isEqualTo(AuditorInternalValues.DEFAULT_BASE_NAMESPACE);
  }

  @Test
  void getBaseNamespace_setGiven_shouldReturnConfiguredValue() {
    // Arrange
    Properties props = new Properties();
    props.setProperty(AuditorInternalValues.AUDITOR_NAMESPACE_PROPERTY, "my_auditor");

    // Act
    AuditorFinalizeConfig config = new AuditorFinalizeConfig(props);

    // Assert
    assertThat(config.getBaseNamespace()).isEqualTo("my_auditor");
  }
}
