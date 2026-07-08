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
 * <p>Connection and workload parameters are read from {@code -De2e.*} system properties. The client
 * talks to the servers over gRPC (via kubectl port-forward on the host); it never touches Cosmos
 * directly — the servers do.
 *
 * <p>Two modes ({@code -De2e.mode}):
 *
 * <ul>
 *   <li>{@code committed} (default) — with both servers up, bootstrap the client, register the
 *       contract, and run N committed writes. Produces "old data".
 *   <li>{@code stranded} — with the Ledger scaled to 0, run a single write. The Auditor takes a
 *       write lock and writes a request_proof, but the write cannot commit, leaving a stranded held
 *       lock (the scenario auditor-finalize-records exists for). Assumes {@code committed} already
 *       ran (so the client identity and contract are registered): it neither bootstraps nor
 *       registers, since both require the Ledger.
 * </ul>
 */
public final class PopulateMain {

  private static final String CONTRACT_ID = "PutAsset";

  private PopulateMain() {}

  public static void main(String[] args) throws Exception {
    String entityId = prop("e2e.client.entity.id", "e2e-client");
    String runId = prop("e2e.run.id", "local");
    String mode = prop("e2e.mode", "committed");
    Path outputDir = Paths.get(prop("e2e.output.dir", "build/e2e"));

    Properties props = new Properties();
    props.setProperty("scalar.dl.client.server.host", prop("e2e.ledger.host", "localhost"));
    props.setProperty("scalar.dl.client.server.port", prop("e2e.ledger.port", "50051"));
    props.setProperty(
        "scalar.dl.client.server.privileged_port", prop("e2e.ledger.privileged_port", "50052"));
    props.setProperty("scalar.dl.client.auditor.enabled", "true");
    props.setProperty("scalar.dl.client.auditor.host", prop("e2e.auditor.host", "localhost"));
    props.setProperty("scalar.dl.client.auditor.port", prop("e2e.auditor.port", "40051"));
    props.setProperty(
        "scalar.dl.client.auditor.privileged_port", prop("e2e.auditor.privileged_port", "40052"));
    props.setProperty("scalar.dl.client.authentication.method", "hmac");
    props.setProperty("scalar.dl.client.entity.id", entityId);
    props.setProperty(
        "scalar.dl.client.entity.identity.hmac.secret_key",
        prop("e2e.client.hmac.secret", "e2e-client-hmac-secret-disposable-0123456789"));

    ObjectMapper mapper = new ObjectMapper();
    ClientServiceFactory factory = new ClientServiceFactory();
    try {
      if ("committed".equals(mode)) {
        // autoBootstrap=true registers this client's HMAC secret on both servers and the
        // linearizable-validation contract used by auditor mode.
        ClientService client = factory.create(new ClientConfig(props), true);
        registerContractIdempotently(client);
        int count = Integer.parseInt(prop("e2e.populate.count", "4"));
        List<String> ids = runCommitted(client, mapper, runId, count);
        mergeSeedMetadata(mapper, outputDir, runId, entityId, "committedAssetIds", ids);
        System.out.println("Populate(committed) complete: " + ids.size() + " committed assets");
      } else if ("stranded".equals(mode)) {
        // The Ledger is down, so we cannot bootstrap or register (both hit the Ledger). Reuse the
        // identity/contract registered by the earlier committed run.
        ClientService client = factory.create(new ClientConfig(props), false);
        String id = runStranded(client, mapper, runId);
        mergeSeedMetadata(
            mapper, outputDir, runId, entityId, "strandedAssetIds", Collections.singletonList(id));
        System.out.println("Populate(stranded) complete: stranded asset " + id);
      } else {
        throw new IllegalArgumentException("Unknown e2e.mode: " + mode);
      }
    } finally {
      factory.close();
    }
    System.out.println("seed-metadata at " + outputDir.resolve("seed-metadata.json"));
  }

  /**
   * Registers the contract, tolerating a prior registration. The shared E2E Cosmos account persists
   * registrations across runs, so a repeat registration returns CONTRACT_ALREADY_REGISTERED, which
   * is benign here.
   */
  private static void registerContractIdempotently(ClientService client) throws IOException {
    try {
      client.registerContract(
          CONTRACT_ID, PutAssetContract.class.getName(), classBytes(PutAssetContract.class));
      System.out.println("Registered contract " + CONTRACT_ID);
    } catch (ClientException e) {
      if (e.getStatusCode() == StatusCode.CONTRACT_ALREADY_REGISTERED) {
        System.out.println("Contract " + CONTRACT_ID + " already registered; reusing");
      } else {
        throw e;
      }
    }
  }

  private static List<String> runCommitted(
      ClientService client, ObjectMapper mapper, String runId, int count) {
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      String assetId = "e2e-" + runId + "-asset-" + i;
      ObjectNode arg = mapper.createObjectNode();
      arg.put("asset_id", assetId);
      arg.put("amount", 100 + i);
      // In auditor mode a normally-returning executeContract means the write was committed on the
      // Ledger AND validated by the Auditor, so the asset/request_proof/coordinator.state/released
      // asset_lock records now exist in Cosmos.
      client.executeContract(CONTRACT_ID, arg);
      ids.add(assetId);
      System.out.println("Committed asset " + assetId);
    }
    return ids;
  }

  private static String runStranded(ClientService client, ObjectMapper mapper, String runId) {
    String assetId = "e2e-" + runId + "-stranded-0";
    ObjectNode arg = mapper.createObjectNode();
    arg.put("asset_id", assetId);
    arg.put("amount", 999);
    boolean threw = false;
    try {
      client.executeContract(CONTRACT_ID, arg);
    } catch (RuntimeException expected) {
      threw = true;
      System.out.println(
          "Stranded write failed as expected ("
              + expected.getClass().getSimpleName()
              + "): the Auditor holds the lock but the Ledger could not commit");
    }
    if (!threw) {
      throw new IllegalStateException(
          "Stranded write unexpectedly succeeded; the Ledger was supposed to be down");
    }
    return assetId;
  }

  /**
   * Reads the seed-metadata file if present, sets {@code arrayField} to {@code ids}, writes back.
   */
  private static void mergeSeedMetadata(
      ObjectMapper mapper,
      Path outputDir,
      String runId,
      String entityId,
      String arrayField,
      List<String> ids)
      throws IOException {
    Files.createDirectories(outputDir);
    File file = outputDir.resolve("seed-metadata.json").toFile();
    ObjectNode root =
        file.exists() ? (ObjectNode) mapper.readTree(file) : mapper.createObjectNode();
    root.put("runId", runId);
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

  private static String prop(String key, String defaultValue) {
    String v = System.getProperty(key);
    return (v == null || v.isEmpty()) ? defaultValue : v;
  }
}
