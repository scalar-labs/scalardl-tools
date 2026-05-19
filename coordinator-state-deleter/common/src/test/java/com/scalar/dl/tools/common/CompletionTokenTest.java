package com.scalar.dl.tools.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CompletionTokenTest {

  @Test
  void create_shouldProduceTokenWithCorrectFields() {
    // Arrange

    // Act
    CompletionToken token = CompletionToken.create(CompletionToken.Server.LEDGER, 1745000000000L);

    // Assert
    assertThat(token.getServer()).isEqualTo(CompletionToken.Server.LEDGER);
    assertThat(token.getStartedAtMs()).isEqualTo(1745000000000L);
    assertThat(token.getCrc32c()).isNotEmpty();
  }

  @Test
  void create_sameInputsGiven_shouldProduceSameCrc() {
    // Arrange

    // Act
    CompletionToken t1 = CompletionToken.create(CompletionToken.Server.LEDGER, 1000L);
    CompletionToken t2 = CompletionToken.create(CompletionToken.Server.LEDGER, 1000L);

    // Assert
    assertThat(t1.getCrc32c()).isEqualTo(t2.getCrc32c());
    assertThat(t1.encode()).isEqualTo(t2.encode());
  }

  @Test
  void create_differentInputsGiven_shouldProduceDifferentCrc() {
    // Arrange

    // Act
    CompletionToken t1 = CompletionToken.create(CompletionToken.Server.LEDGER, 1000L);
    CompletionToken t2 = CompletionToken.create(CompletionToken.Server.AUDITOR, 1000L);
    CompletionToken t3 = CompletionToken.create(CompletionToken.Server.LEDGER, 2000L);

    // Assert
    assertThat(t1.getCrc32c()).isNotEqualTo(t2.getCrc32c());
    assertThat(t1.getCrc32c()).isNotEqualTo(t3.getCrc32c());
  }

  @Test
  void encode_shouldProduceShellSafeString() {
    // Arrange
    CompletionToken token = CompletionToken.create(CompletionToken.Server.LEDGER, 1745000000000L);

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
        CompletionToken.create(CompletionToken.Server.LEDGER, 1745000000000L);
    String encoded = original.encode();

    // Act
    CompletionToken decoded = CompletionToken.decode(encoded);

    // Assert
    assertThat(decoded.getServer()).isEqualTo(original.getServer());
    assertThat(decoded.getStartedAtMs()).isEqualTo(original.getStartedAtMs());
    assertThat(decoded.getCrc32c()).isEqualTo(original.getCrc32c());
  }

  @Test
  void decode_corruptedCrcGiven_shouldThrowIllegalArgumentException() {
    // Arrange
    CompletionToken token = CompletionToken.create(CompletionToken.Server.LEDGER, 1745000000000L);
    String encoded = token.encode();
    // Corrupt one character
    char[] chars = encoded.toCharArray();
    chars[chars.length - 1] = chars[chars.length - 1] == 'A' ? 'B' : 'A';
    String corrupted = new String(chars);

    // Act & Assert
    assertThatThrownBy(() -> CompletionToken.decode(corrupted))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void decode_invalidBase64Given_shouldThrowIllegalArgumentException() {
    // Arrange

    // Act & Assert
    assertThatThrownBy(() -> CompletionToken.decode("!!!not-base64!!!"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void decode_invalidJsonGiven_shouldThrowIllegalArgumentException() {
    // Arrange
    CompletionToken token = CompletionToken.create(CompletionToken.Server.LEDGER, 1745000000000L);
    String encoded = token.encode();
    String truncated = encoded.substring(0, encoded.length() / 2);

    // Act & Assert
    assertThatThrownBy(() -> CompletionToken.decode(truncated))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
