package com.scalar.dl.tools.cleanup;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppendOnlyLogTest {

  @TempDir Path tempDir;

  private AppendOnlyLog newLog() {
    return new AppendOnlyLog(tempDir.resolve("test.log"));
  }

  @Test
  void load_noFileGiven_shouldReturnEmptyList() {
    // Arrange

    // Act & Assert
    assertThat(newLog().load()).isEmpty();
  }

  @Test
  void load_afterAppendsGiven_shouldReturnPayloadsInInsertionOrder() {
    // Arrange
    AppendOnlyLog log = newLog();

    // Act
    log.append("a");
    log.append("b");
    log.append("c");

    // Assert
    assertThat(newLog().load()).containsExactly("a", "b", "c");
  }

  @Test
  void load_duplicateAppendsGiven_shouldPreserveEveryPayload() {
    // Arrange
    AppendOnlyLog log = newLog();

    // Act
    log.append("a");
    log.append("a");
    log.append("b");

    // Assert
    assertThat(log.load()).containsExactly("a", "a", "b");
  }

  @Test
  void load_payloadsWithSpecialCharactersGiven_shouldRoundTripCorrectly() {
    // Arrange
    AppendOnlyLog log = newLog();
    String payload = "line1\nline2\t\"quoted\"";

    // Act
    log.append(payload);
    log.append("plain");

    // Assert
    assertThat(newLog().load()).containsExactly(payload, "plain");
  }

  @Test
  void clear_shouldRemoveTheLogFile() {
    // Arrange
    Path path = tempDir.resolve("test.log");
    AppendOnlyLog log = new AppendOnlyLog(path);
    log.append("a");
    assertThat(Files.exists(path)).isTrue();

    // Act
    log.clear();

    // Assert
    assertThat(Files.exists(path)).isFalse();
    assertThat(log.load()).isEmpty();
  }

  @Test
  void clear_noFileGiven_shouldNotThrow() {
    // Arrange

    // Act
    newLog().clear();

    // Assert
    assertThat(newLog().load()).isEmpty();
  }

  @Test
  void append_concurrentWritersGiven_shouldRecordEveryPayload() {
    // Arrange
    AppendOnlyLog log = newLog();
    List<String> payloads =
        IntStream.range(0, 200).mapToObj(i -> "payload" + i).collect(Collectors.toList());

    // Act — append concurrently from multiple threads.
    payloads.parallelStream().forEach(log::append);

    // Assert
    assertThat(newLog().load()).containsExactlyInAnyOrderElementsOf(payloads);
  }
}
