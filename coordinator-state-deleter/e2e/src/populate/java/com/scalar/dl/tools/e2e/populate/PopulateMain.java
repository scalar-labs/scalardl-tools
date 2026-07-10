package com.scalar.dl.tools.e2e.populate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.scalar.dl.client.config.ClientConfig;
import com.scalar.dl.client.exception.ClientException;
import com.scalar.dl.client.service.ClientService;
import com.scalar.dl.client.service.ClientServiceFactory;
import com.scalar.dl.ledger.service.StatusCode;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

/**
 * Populate step of the E2E test. Drives committed auditor-mode writes against a running ScalarDL
 * Ledger + Auditor (3.13.0) so the cleanup tools have realistic "old data" to reclaim, then writes
 * a seed-metadata file describing what was created.
 *
 * <p>Client config is loaded from {@code build/client.properties} (rendered by the workflow from
 * ci/e2e/client.properties, shared with verify); workload parameters ({@code e2e.mode}, {@code
 * e2e.populate.count}, {@code e2e.output.dir}) come from {@code -De2e.*} system properties. The
 * client talks to the servers over gRPC (via kubectl port-forward on the host); it never touches
 * Cosmos directly — the servers do.
 *
 * <p>Two modes ({@code -De2e.mode}):
 *
 * <ul>
 *   <li>{@code committed} (default) — with both servers up, bootstrap the client, register the
 *       contract, and run N committed writes. Produces "old data".
 *   <li>{@code stranded} — with the Ledger scaled to 0, run a put and a get. The Auditor takes a
 *       WRITE lock (put, + request_proof) and a READ lock (get, no request_proof) during ordering,
 *       but neither can complete on the Ledger, leaving two stranded held locks (the scenario
 *       auditor-finalize-records exists for). Assumes {@code committed} already ran (so the client
 *       identity and both contracts are registered): it neither bootstraps nor registers, since
 *       both require the Ledger.
 * </ul>
 */
public final class PopulateMain {

  private static final String PUT_CONTRACT_ID = "PutAsset";
  private static final String GET_CONTRACT_ID = "GetAsset";
  // The stranded READ lock is taken on an existing committed asset (written by the committed run).
  private static final String STRANDED_READ_ASSET_ID = "e2e-asset-0";
  // Rendered by the workflow (from ci/e2e/client.properties); relative to the e2e module dir.
  private static final String CLIENT_PROPERTIES_PATH = "build/client.properties";

  private PopulateMain() {}

  public static void main(String[] args) throws Exception {
    String mode = getProperty("e2e.mode", "committed");
    Path outputDir = Paths.get(getProperty("e2e.output.dir", "build/e2e"));

    Properties props = loadClientProperties();
    String entityId = props.getProperty(ClientConfig.ENTITY_ID, "e2e-client");
    ObjectMapper mapper = new ObjectMapper();
    ClientServiceFactory factory = new ClientServiceFactory();
    try {
      if ("committed".equals(mode)) {
        // autoBootstrap=true registers this client's HMAC secret on both servers and the
        // linearizable-validation contract used by auditor mode.
        ClientService client = factory.create(new ClientConfig(props), true);
        registerContractIdempotently(client, PUT_CONTRACT_ID, PutAssetContract.class);
        registerContractIdempotently(client, GET_CONTRACT_ID, GetAssetContract.class);
        int count = Integer.parseInt(getProperty("e2e.populate.count", "4"));
        List<String> ids = runCommitted(client, mapper, count);
        mergeSeedMetadata(mapper, outputDir, entityId, "committedAssetIds", ids);
        System.out.println("Populate(committed) complete: " + ids.size() + " committed assets");
      } else if ("stranded".equals(mode)) {
        // The Ledger is down, so we cannot bootstrap or register (both hit the Ledger). Reuse the
        // identity/contracts registered by the earlier committed run. Leave both a held WRITE lock
        // (put) and a held READ lock (get) for auditor-finalize-records to recover.
        ClientService client = factory.create(new ClientConfig(props), false);
        String writeId = runStrandedWrite(client, mapper);
        String readId = runStrandedRead(client, mapper);
        mergeSeedMetadata(
            mapper, outputDir, entityId, "strandedAssetIds", Collections.singletonList(writeId));
        mergeSeedMetadata(
            mapper, outputDir, entityId, "strandedReadAssetIds", Collections.singletonList(readId));
        System.out.println(
            "Populate(stranded) complete: WRITE lock on " + writeId + ", READ lock on " + readId);
      } else {
        throw new IllegalArgumentException("Unknown e2e.mode: " + mode);
      }
    } finally {
      factory.close();
    }
    System.out.println("seed-metadata at " + outputDir.resolve("seed-metadata.json"));
  }

  /**
   * Loads the client properties from {@code build/client.properties}, which the workflow renders
   * from ci/e2e/client.properties (with the Cosmos credentials filled in) and shares with verify.
   * The path is relative to the e2e module dir (this task's working dir). Its {@code
   * scalar.dl.client.*} keys configure the client; the {@code scalar.db.*} keys are ignored by
   * ClientConfig.
   */
  private static Properties loadClientProperties() throws IOException {
    Properties props = new Properties();
    try (InputStream in = Files.newInputStream(Paths.get(CLIENT_PROPERTIES_PATH))) {
      props.load(in);
    }
    return props;
  }

  /**
   * Registers a contract, tolerating a prior registration. The shared E2E Cosmos account persists
   * registrations across runs, so a repeat registration returns CONTRACT_ALREADY_REGISTERED, which
   * is benign here.
   */
  private static void registerContractIdempotently(
      ClientService client, String contractId, Class<?> contractClass) throws IOException {
    try {
      client.registerContract(contractId, contractClass.getName(), classBytes(contractClass));
      System.out.println("Registered contract " + contractId);
    } catch (ClientException e) {
      if (e.getStatusCode() == StatusCode.CONTRACT_ALREADY_REGISTERED) {
        System.out.println("Contract " + contractId + " already registered; reusing");
      } else {
        throw e;
      }
    }
  }

  private static List<String> runCommitted(ClientService client, ObjectMapper mapper, int count) {
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      String assetId = "e2e-asset-" + i;
      ObjectNode arg = mapper.createObjectNode();
      arg.put("asset_id", assetId);
      arg.put("amount", 100 + i);
      // In auditor mode a normally-returning executeContract means the write was committed on the
      // Ledger AND validated by the Auditor, so the asset/request_proof/coordinator.state/released
      // asset_lock records now exist in Cosmos.
      client.executeContract(PUT_CONTRACT_ID, arg);
      ids.add(assetId);
      System.out.println("Committed asset " + assetId);
    }
    return ids;
  }

  /** Leaves a held WRITE lock: a put whose Auditor ordering succeeds but Ledger commit cannot. */
  private static String runStrandedWrite(ClientService client, ObjectMapper mapper) {
    String assetId = "e2e-stranded-0";
    ObjectNode arg = mapper.createObjectNode();
    arg.put("asset_id", assetId);
    arg.put("amount", 999);
    executeExpectingLedgerDown(client, PUT_CONTRACT_ID, arg, "write", assetId);
    return assetId;
  }

  /**
   * Leaves a held READ lock: a get on an existing committed asset whose Auditor ordering takes a
   * READ lock but whose Ledger execution cannot complete.
   */
  private static String runStrandedRead(ClientService client, ObjectMapper mapper) {
    String assetId = STRANDED_READ_ASSET_ID;
    ObjectNode arg = mapper.createObjectNode();
    arg.put("asset_id", assetId);
    executeExpectingLedgerDown(client, GET_CONTRACT_ID, arg, "read", assetId);
    return assetId;
  }

  /** Runs a contract that must fail because the Ledger is down, leaving the Auditor lock held. */
  private static void executeExpectingLedgerDown(
      ClientService client, String contractId, ObjectNode arg, String lockKind, String assetId) {
    boolean threw = false;
    try {
      client.executeContract(contractId, arg);
    } catch (RuntimeException expected) {
      threw = true;
      System.out.println(
          "Stranded "
              + lockKind
              + " on "
              + assetId
              + " failed as expected ("
              + expected.getClass().getSimpleName()
              + "): the Auditor holds the "
              + lockKind
              + " lock but the Ledger could not complete");
    }
    if (!threw) {
      throw new IllegalStateException(
          "Stranded " + lockKind + " unexpectedly succeeded; the Ledger was supposed to be down");
    }
  }

  /**
   * Reads the seed-metadata file if present, sets {@code arrayField} to {@code ids}, writes back.
   */
  private static void mergeSeedMetadata(
      ObjectMapper mapper, Path outputDir, String entityId, String arrayField, List<String> ids)
      throws IOException {
    Files.createDirectories(outputDir);
    File file = outputDir.resolve("seed-metadata.json").toFile();
    ObjectNode root =
        file.exists() ? (ObjectNode) mapper.readTree(file) : mapper.createObjectNode();
    root.put("entityId", entityId);
    ArrayNode arr = root.putArray(arrayField);
    ids.forEach(arr::add);
    mapper.writerWithDefaultPrettyPrinter().writeValue(file, root);
  }

  private static byte[] classBytes(Class<?> clazz) throws IOException {
    String resource = clazz.getName().replace('.', '/') + ".class";
    try (InputStream in = clazz.getClassLoader().getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("Cannot find class bytes for " + resource);
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      int read;
      while ((read = in.read(buffer)) != -1) {
        out.write(buffer, 0, read);
      }
      return out.toByteArray();
    }
  }

  private static String getProperty(String key, String defaultValue) {
    String v = System.getProperty(key);
    return (v == null || v.isEmpty()) ? defaultValue : v;
  }
}
