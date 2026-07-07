package com.scalar.dl.tools.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class CompletionTokenTest {

  @Test
  void create_shouldProduceTokenWithCorrectFields() {
    // Arrange

    // Act
    CompletionToken token =
        CompletionToken.create(CompletionToken.ServerType.LEDGER, 1745000000000L);

    // Assert
    assertThat(token.getServerType()).isEqualTo(CompletionToken.ServerType.LEDGER);
    assertThat(token.getStartedAtMs()).isEqualTo(1745000000000L);
    assertThat(token.getCrc32c()).isNotEmpty();
  }

  @Test
  void create_sameInputsGiven_shouldProduceSameCrc() {
    // Arrange

    // Act
    CompletionToken t1 = CompletionToken.create(CompletionToken.ServerType.LEDGER, 1000L);
    CompletionToken t2 = CompletionToken.create(CompletionToken.ServerType.LEDGER, 1000L);

    // Assert
    assertThat(t1.getCrc32c()).isEqualTo(t2.getCrc32c());
    assertThat(t1.encode()).isEqualTo(t2.encode());
  }

  @Test
  void create_differentInputsGiven_shouldProduceDifferentCrc() {
    // Arrange

    // Act
    CompletionToken t1 = CompletionToken.create(CompletionToken.ServerType.LEDGER, 1000L);
    CompletionToken t2 = CompletionToken.create(CompletionToken.ServerType.AUDITOR, 1000L);
    CompletionToken t3 = CompletionToken.create(CompletionToken.ServerType.LEDGER, 2000L);

    // Assert
    assertThat(t1.getCrc32c()).isNotEqualTo(t2.getCrc32c());
    assertThat(t1.getCrc32c()).isNotEqualTo(t3.getCrc32c());
  }

  @Test
  void encode_shouldProduceShellSafeString() {
    // Arrange
    CompletionToken token =
        CompletionToken.create(CompletionToken.ServerType.LEDGER, 1745000000000L);

    // Act
    String encoded = token.encode();

    // Assert
    // Only alphanumeric, hyphen, and underscore
    assertThat(encoded).matches("[A-Za-z0-9_-]+");
  }

  @Test
  void decode_shouldDecodeEncodedTokenCorrectly() {
    // Arrange
    CompletionToken original =
        CompletionToken.create(CompletionToken.ServerType.LEDGER, 1745000000000L);
    String encoded = original.encode();

    // Act
    CompletionToken decoded = CompletionToken.decode(encoded);

    // Assert
    assertThat(decoded.getServerType()).isEqualTo(original.getServerType());
    assertThat(decoded.getStartedAtMs()).isEqualTo(original.getStartedAtMs());
    assertThat(decoded.getCrc32c()).isEqualTo(original.getCrc32c());
  }

  @Test
  void decode_crcMismatchGiven_shouldThrowExceptionWithCrcMismatchCode() {
    // Arrange: a well-formed token payload whose CRC32C does not match its contents.
    String json =
        "{\"server_type\":\"ledger\",\"started_at_ms\":1745000000000,\"crc32c\":\"deadbeef\"}";
    String encoded =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(json.getBytes(StandardCharsets.UTF_8));

    // Act & Assert
    assertThatThrownBy(() -> CompletionToken.decode(encoded))
        .isInstanceOf(CoordinatorStateDeleterException.class)
        .hasMessageContaining(
            CoordinatorStateDeleterError.COMPLETION_TOKEN_CRC_MISMATCH.buildCode());
  }

  @Test
  void decode_invalidBase64Given_shouldThrowException() {
    // Arrange

    // Act & Assert
    assertThatThrownBy(() -> CompletionToken.decode("!!!not-base64!!!"))
        .isInstanceOf(CoordinatorStateDeleterException.class)
        .hasMessageContaining(
            CoordinatorStateDeleterError.COMPLETION_TOKEN_DECODE_FAILED.buildCode())
        .hasCauseInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void decode_invalidJsonGiven_shouldThrowException() {
    // Arrange: valid base64url whose decoded payload is not valid JSON, so decoding fails while
    // parsing the JSON (as opposed to while decoding base64).
    String encoded =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString("{not valid json}".getBytes(StandardCharsets.UTF_8));

    // Act & Assert
    assertThatThrownBy(() -> CompletionToken.decode(encoded))
        .isInstanceOf(CoordinatorStateDeleterException.class)
        .hasMessageContaining(
            CoordinatorStateDeleterError.COMPLETION_TOKEN_DECODE_FAILED.buildCode())
        .hasCauseInstanceOf(JsonProcessingException.class);
  }
}
