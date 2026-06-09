package com.scalar.dl.tools.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.hash.Hashing;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.annotation.concurrent.Immutable;

/**
 * Self-describing completion token exchanged between AD operators.
 *
 * <p>Each finalization command ({@code ledger-finalize-records}, {@code auditor-finalize-records})
 * emits a completion token upon successful completion. The two tokens are then passed to {@code
 * coordinator-state-cleanup}, which uses them to determine the safe deletion window.
 *
 * <p>The payload is JSON containing the server type, the guarantee timestamp ({@code
 * started_at_ms}), and a CRC32C checksum. It is wrapped in base64url so that it forms a single,
 * shell-safe string suitable for copy-paste between operators and CLI arguments.
 */
@Immutable
public final class CompletionToken {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private final ServerType serverType;
  private final long startedAtMs;
  private final String crc32c;

  private CompletionToken(ServerType serverType, long startedAtMs, String crc32c) {
    this.serverType = serverType;
    this.startedAtMs = startedAtMs;
    this.crc32c = crc32c;
  }

  /**
   * Creates a new completion token for the given server type and guarantee timestamp.
   *
   * @param serverType the server type that performed the finalization
   * @param startedAtMs the guarantee timestamp captured at the start of the finalization sweep
   * @return a new completion token with a computed CRC32C
   */
  public static CompletionToken create(ServerType serverType, long startedAtMs) {
    String crc = computeCrc32c(serverType.getValue(), startedAtMs);
    return new CompletionToken(serverType, startedAtMs, crc);
  }

  /**
   * Decodes a base64url-encoded token string and validates its CRC32C checksum.
   *
   * @param encoded the base64url-encoded token string
   * @return the decoded completion token
   * @throws IllegalArgumentException if the string cannot be decoded or the CRC32C does not match
   */
  public static CompletionToken decode(String encoded) {
    try {
      byte[] bytes = Base64.getUrlDecoder().decode(encoded);
      JsonNode node = MAPPER.readTree(bytes);
      String serverValue = node.get("server_type").asText();
      ServerType serverType = ServerType.fromValue(serverValue);
      long startedAtMs = node.get("started_at_ms").asLong();
      String crc = node.get("crc32c").asText();

      String expected = computeCrc32c(serverValue, startedAtMs);
      if (!expected.equals(crc)) {
        throw new IllegalArgumentException(
            "CRC32C mismatch: expected " + expected + " but got " + crc);
      }
      return new CompletionToken(serverType, startedAtMs, crc);
    } catch (IllegalArgumentException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalArgumentException("Failed to decode completion token", e);
    }
  }

  /**
   * Computes a CRC32C checksum over the server type and timestamp. CRC32C is used for
   * accidental-corruption detection, not tamper protection. Both AD operators are assumed to be
   * trusted.
   */
  private static String computeCrc32c(String server, long startedAtMs) {
    String input = server + startedAtMs;
    return Hashing.crc32c().hashString(input, StandardCharsets.UTF_8).toString();
  }

  /**
   * Encodes this token as a base64url string (no padding).
   *
   * @return the encoded token string, suitable for use as a CLI argument
   */
  public String encode() {
    try {
      ObjectNode node = MAPPER.createObjectNode();
      node.put("server_type", serverType.getValue());
      node.put("started_at_ms", startedAtMs);
      node.put("crc32c", crc32c);
      byte[] json = MAPPER.writeValueAsBytes(node);
      return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
    } catch (Exception e) {
      throw new RuntimeException("Failed to encode completion token", e);
    }
  }

  /** Returns the server type that produced this token. */
  public ServerType getServerType() {
    return serverType;
  }

  /** Returns the guarantee timestamp captured at the start of the finalization sweep. */
  public long getStartedAtMs() {
    return startedAtMs;
  }

  /** Returns the CRC32C checksum string. */
  public String getCrc32c() {
    return crc32c;
  }

  /** The server type that produced the completion token. */
  public enum ServerType {
    LEDGER("ledger"),
    AUDITOR("auditor");

    private final String value;

    ServerType(String value) {
      this.value = value;
    }

    static ServerType fromValue(String value) {
      for (ServerType s : values()) {
        if (s.value.equals(value)) {
          return s;
        }
      }
      throw new IllegalArgumentException("Unknown server type: " + value);
    }

    public String getValue() {
      return value;
    }
  }
}
