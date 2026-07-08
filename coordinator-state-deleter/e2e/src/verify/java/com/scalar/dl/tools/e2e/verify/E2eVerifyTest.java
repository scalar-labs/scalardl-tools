package com.scalar.dl.tools.e2e.verify;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scalar.db.api.DistributedStorage;
import com.scalar.db.api.Get;
import com.scalar.db.io.Key;
import com.scalar.db.service.StorageFactory;
import com.scalar.dl.tools.cli.CoordinatorStateDeleter;
import com.scalar.dl.tools.common.AuditorInternalValues;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.Properties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * End-to-end verification: runs the three coordinator-state-deleter CLI commands in-process against
 * the running 3.14.0-SNAPSHOT Ledger + Auditor (and the shared Cosmos DB), exercising the real CLI
 * wiring and the real Auditor {@code RecoverAssetLock} gRPC path against data written by 3.13.0.
 *
 * <p>Beyond exit codes and tokens, this reads Cosmos directly to assert that the stranded held lock
 * seeded by populate is actually released by {@code auditor-finalize-records} (held before,
 * released after) — proving the RecoverAssetLock path did real work, not just returned 0.
 *
 * <p>Connection details come from system properties: {@code -Dscalardb.cosmos.uri} / {@code
 * -Dscalardb.cosmos.password} for Cosmos, and {@code -De2e.*} for the port-forwarded server
 * targets. The stranded asset id is read from the seed-metadata file written by populate.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class E2eVerifyTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private Path propertiesFile;
  private Path checkpointRoot;
  private DistributedStorage storage;
  private String strandedAssetId;

  @BeforeAll
  void setUp() throws Exception {
    String cosmosUri = required("scalardb.cosmos.uri");
    String cosmosKey = required("scalardb.cosmos.password");

    Properties props = new Properties();
    props.setProperty("scalar.db.storage", "cosmos");
    props.setProperty("scalar.db.contact_points", cosmosUri);
    props.setProperty("scalar.db.password", cosmosKey);
    props.setProperty("scalar.dl.client.server.host", prop("e2e.ledger.host", "127.0.0.1"));
    props.setProperty("scalar.dl.client.server.port", prop("e2e.ledger.port", "50051"));
    props.setProperty(
        "scalar.dl.client.server.privileged_port", prop("e2e.ledger.privileged_port", "50052"));
    props.setProperty("scalar.dl.client.auditor.enabled", "true");
    props.setProperty("scalar.dl.client.auditor.host", prop("e2e.auditor.host", "127.0.0.1"));
    props.setProperty("scalar.dl.client.auditor.port", prop("e2e.auditor.port", "40051"));
    props.setProperty(
        "scalar.dl.client.auditor.privileged_port", prop("e2e.auditor.privileged_port", "40052"));
    props.setProperty("scalar.dl.client.authentication.method", "hmac");
    props.setProperty("scalar.dl.client.entity.id", prop("e2e.client.entity.id", "e2e-client"));
    props.setProperty(
        "scalar.dl.client.entity.identity.hmac.secret_key",
        prop("e2e.client.hmac.secret", "e2e-client-hmac-secret-disposable-0123456789"));

    Path dir = Files.createTempDirectory("e2e-verify");
    propertiesFile = dir.resolve("client.properties");
    try (java.io.OutputStream out = Files.newOutputStream(propertiesFile)) {
      props.store(out, "e2e verify");
    }
    checkpointRoot = Files.createDirectories(dir.resolve("checkpoints"));

    // Storage handle for Cosmos-level assertions (StorageFactory ignores the scalar.dl.* keys).
    storage = StorageFactory.create(props).getStorage();
    strandedAssetId = readStrandedAssetId();
  }

  @AfterAll
  void tearDown() {
    if (storage != null) {
      storage.close();
    }
  }

  @Test
  void pipeline_shouldFinalizeRecordsAndCleanUpCoordinatorState() throws Exception {
    // 1) Ledger side: finalize non-terminal records, emit the ledger token.
    CliResult ledger = run("ledger-finalize-records", checkpoint("ledger"));
    assertThat(ledger.exitCode).as("ledger-finalize-records exit code\n%s", ledger.stdout).isZero();
    String ledgerToken = ledger.completionToken();
    assertThat(ledgerToken).as("ledger completion token").isNotEmpty();

    // Sanity: the stranded lock seeded by populate must still be HELD before recovery. This also
    // confirms the populate stranded-lock generation actually worked.
    assertThat(strandedLockHeld())
        .as("stranded asset %s should hold a lock before auditor-finalize", strandedAssetId)
        .isTrue();

    // 2) Auditor side: release the stranded lock via the real RecoverAssetLock gRPC path and clean
    //    settled request_proof records, emit the auditor token.
    CliResult auditor = run("auditor-finalize-records", checkpoint("auditor"));
    assertThat(auditor.exitCode)
        .as("auditor-finalize-records exit code\n%s", auditor.stdout)
        .isZero();
    String auditorToken = auditor.completionToken();
    assertThat(auditorToken).as("auditor completion token").isNotEmpty();

    // The core assertion: RecoverAssetLock actually released the stranded lock.
    assertThat(strandedLockHeld())
        .as("stranded asset %s lock should be released after auditor-finalize", strandedAssetId)
        .isFalse();

    // 3) Delete coordinator-state records that are safe given both tokens.
    CliResult cleanup =
        run(
            "coordinator-state-cleanup",
            checkpoint("cleanup"),
            "--ledger-token",
            ledgerToken,
            "--auditor-token",
            auditorToken);
    assertThat(cleanup.exitCode)
        .as("coordinator-state-cleanup exit code\n%s", cleanup.stdout)
        .isZero();
    assertThat(cleanup.statusCode()).as("coordinator-state-cleanup status").isEqualTo("OK");
  }

  /**
   * Returns whether the stranded asset's {@code asset_lock} row is currently held (present with a
   * non-NONE lock type). The partition key of the asset_lock table is the asset id itself.
   */
  private boolean strandedLockHeld() throws Exception {
    Get get =
        Get.newBuilder()
            .namespace(AuditorInternalValues.DEFAULT_BASE_NAMESPACE)
            .table(AuditorInternalValues.ASSET_LOCK_TABLE_NAME)
            .partitionKey(
                Key.ofText(AuditorInternalValues.ASSET_LOCK_TABLE_ID_COLUMN_NAME, strandedAssetId))
            .build();
    Optional<com.scalar.db.api.Result> row = storage.get(get);
    if (!row.isPresent()) {
      return false;
    }
    int lockType = row.get().getInt(AuditorInternalValues.ASSET_LOCK_TABLE_LOCK_TYPE_COLUMN_NAME);
    return lockType != AuditorInternalValues.LOCK_TYPE_NONE;
  }

  private String readStrandedAssetId() throws Exception {
    Path seed = Paths.get(prop("e2e.output.dir", "build/e2e")).resolve("seed-metadata.json");
    JsonNode root = MAPPER.readTree(seed.toFile());
    JsonNode ids = root.get("strandedAssetIds");
    if (ids == null || !ids.isArray() || ids.isEmpty()) {
      throw new IllegalStateException("No strandedAssetIds in seed-metadata: " + seed);
    }
    return ids.get(0).asText();
  }

  private String[] checkpoint(String name) {
    return new String[] {
      "--properties", propertiesFile.toString(),
      "--checkpoint-dir", checkpointRoot.resolve(name).toString()
    };
  }

  /** Runs a subcommand in-process, capturing the JSON it writes to stdout. */
  private CliResult run(String command, String[] baseArgs, String... extraArgs) {
    String[] args = new String[1 + baseArgs.length + extraArgs.length];
    args[0] = command;
    System.arraycopy(baseArgs, 0, args, 1, baseArgs.length);
    System.arraycopy(extraArgs, 0, args, 1 + baseArgs.length, extraArgs.length);

    PrintStream original = System.out;
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    int exitCode;
    try {
      System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8.name()));
      exitCode = new picocli.CommandLine(new CoordinatorStateDeleter()).execute(args);
    } catch (Exception e) {
      throw new RuntimeException("Failed to run " + command, e);
    } finally {
      System.setOut(original);
    }
    String stdout = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    original.println("[" + command + "] exit=" + exitCode + "\n" + stdout);
    return new CliResult(exitCode, stdout);
  }

  private static String prop(String key, String defaultValue) {
    String v = System.getProperty(key);
    return (v == null || v.isEmpty()) ? defaultValue : v;
  }

  private static String required(String key) {
    String v = System.getProperty(key);
    if (v == null || v.isEmpty()) {
      throw new IllegalStateException("System property " + key + " must be set");
    }
    return v;
  }

  /** Parsed result of one CLI invocation: {@code {"status_code":..,"output":{..}}}. */
  private static final class CliResult {
    final int exitCode;
    final String stdout;

    CliResult(int exitCode, String stdout) {
      this.exitCode = exitCode;
      this.stdout = stdout;
    }

    String statusCode() {
      JsonNode node = parse();
      return node.has("status_code") ? node.get("status_code").asText() : null;
    }

    String completionToken() {
      JsonNode output = parse().get("output");
      if (output == null || output.isNull() || !output.has("completion_token")) {
        return null;
      }
      return output.get("completion_token").asText();
    }

    private JsonNode parse() {
      try {
        return MAPPER.readTree(stdout);
      } catch (Exception e) {
        throw new RuntimeException("Command stdout was not valid JSON:\n" + stdout, e);
      }
    }
  }
}
