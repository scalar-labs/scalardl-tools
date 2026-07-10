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
import java.io.InputStream;
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
 * <p>Beyond exit codes and tokens, this reads Cosmos directly to assert that both stranded held
 * locks seeded by populate — a WRITE lock (put) and a READ lock (get) — are actually released by
 * {@code auditor-finalize-records} (held before, released after), proving the RecoverAssetLock path
 * did real work for both lock types, not just returned 0.
 *
 * <p>Connection details come from {@code build/client.properties} (rendered by the workflow from
 * ci/e2e/client.properties, shared with populate: ScalarDB Cosmos + ScalarDL client config). The
 * stranded asset ids are read from the seed-metadata file written by populate.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class E2eVerifyTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  // Rendered by the workflow (from ci/e2e/client.properties); relative to the e2e module dir.
  private static final String CLIENT_PROPERTIES_PATH = "build/client.properties";

  private Path propertiesFile;
  private Path checkpointRoot;
  private DistributedStorage storage;
  private String strandedAssetId;
  private String strandedReadAssetId;

  @BeforeAll
  void setUp() throws Exception {
    // The CLI and these tests share the properties file the workflow renders from
    // ci/e2e/client.properties (Cosmos credentials filled in via envsubst).
    propertiesFile = Paths.get(CLIENT_PROPERTIES_PATH);
    Properties props = new Properties();
    try (InputStream in = Files.newInputStream(propertiesFile)) {
      props.load(in);
    }

    checkpointRoot =
        Files.createDirectories(Files.createTempDirectory("e2e-verify").resolve("checkpoints"));

    // Storage handle for Cosmos-level assertions (StorageFactory reads scalar.db.* and ignores the
    // scalar.dl.* keys in the same file).
    storage = StorageFactory.create(props).getStorage();
    strandedAssetId = readSeedAssetId("strandedAssetIds");
    strandedReadAssetId = readSeedAssetId("strandedReadAssetIds");
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

    // Sanity: the stranded locks seeded by populate must still be HELD before recovery (WRITE from
    // the put, READ from the get). This also confirms the populate stranded-lock generation worked.
    assertThat(strandedLockHeld(strandedAssetId))
        .as(
            "stranded WRITE-lock asset %s should hold a lock before auditor-finalize",
            strandedAssetId)
        .isTrue();
    assertThat(strandedLockHeld(strandedReadAssetId))
        .as(
            "stranded READ-lock asset %s should hold a lock before auditor-finalize",
            strandedReadAssetId)
        .isTrue();

    // 2) Auditor side: release the stranded locks via the real RecoverAssetLock gRPC path and clean
    //    settled request_proof records, emit the auditor token.
    CliResult auditor = run("auditor-finalize-records", checkpoint("auditor"));
    assertThat(auditor.exitCode)
        .as("auditor-finalize-records exit code\n%s", auditor.stdout)
        .isZero();
    String auditorToken = auditor.completionToken();
    assertThat(auditorToken).as("auditor completion token").isNotEmpty();

    // The core assertion: RecoverAssetLock actually released both stranded locks (WRITE and READ).
    assertThat(strandedLockHeld(strandedAssetId))
        .as(
            "stranded WRITE-lock asset %s should be released after auditor-finalize",
            strandedAssetId)
        .isFalse();
    assertThat(strandedLockHeld(strandedReadAssetId))
        .as(
            "stranded READ-lock asset %s should be released after auditor-finalize",
            strandedReadAssetId)
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
   * Returns whether the given asset's {@code asset_lock} row is currently held (present with a
   * non-NONE lock type). The partition key of the asset_lock table is the asset id itself.
   */
  private boolean strandedLockHeld(String assetId) throws Exception {
    Get get =
        Get.newBuilder()
            .namespace(AuditorInternalValues.DEFAULT_BASE_NAMESPACE)
            .table(AuditorInternalValues.ASSET_LOCK_TABLE_NAME)
            .partitionKey(
                Key.ofText(AuditorInternalValues.ASSET_LOCK_TABLE_ID_COLUMN_NAME, assetId))
            .build();
    Optional<com.scalar.db.api.Result> row = storage.get(get);
    if (!row.isPresent()) {
      return false;
    }
    int lockType = row.get().getInt(AuditorInternalValues.ASSET_LOCK_TABLE_LOCK_TYPE_COLUMN_NAME);
    return lockType != AuditorInternalValues.LOCK_TYPE_NONE;
  }

  private String readSeedAssetId(String field) throws Exception {
    Path seed = Paths.get(prop("e2e.output.dir", "build/e2e")).resolve("seed-metadata.json");
    JsonNode root = MAPPER.readTree(seed.toFile());
    JsonNode ids = root.get(field);
    if (ids == null || !ids.isArray() || ids.isEmpty()) {
      throw new IllegalStateException("No " + field + " in seed-metadata: " + seed);
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
