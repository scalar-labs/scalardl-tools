package com.scalar.dl.tools.scan.cosmos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CheckpointManagerTest {

  @TempDir Path tempDir;
  private CheckpointManager manager;

  @BeforeEach
  void setUp() {
    manager = new CheckpointManager(tempDir.resolve("checkpoint"));
  }

  @Test
  void loadContinuationToken_nonExistentTokenGiven_shouldReturnNull() {
    // Arrange

    // Act
    String token = manager.loadContinuationToken("ns.table", "range1");

    // Assert
    assertThat(token).isNull();
  }

  @Test
  void loadContinuationToken_existentFileGiven_shouldReturnContent() throws IOException {
    // Arrange
    Path dir = tempDir.resolve("checkpoint").resolve("ns.table");
    Files.createDirectories(dir);
    Files.write(dir.resolve("range1.token"), "saved-token".getBytes(StandardCharsets.UTF_8));

    // Act
    String loaded = manager.loadContinuationToken("ns.table", "range1");

    // Assert
    assertThat(loaded).isEqualTo("saved-token");
  }

  @Test
  void loadContinuationToken_onlyTmpFilePresent_shouldReturnNull() throws IOException {
    // Arrange
    // Simulate a crash that left only the .tmp file
    Path dir = tempDir.resolve("checkpoint").resolve("ns.table");
    Files.createDirectories(dir);
    Path tmpPath = dir.resolve("range1.token.tmp");
    Files.write(tmpPath, "incomplete-token".getBytes(StandardCharsets.UTF_8));

    // Act
    String loaded = manager.loadContinuationToken("ns.table", "range1");

    // Assert
    assertThat(loaded).isNull();
  }

  @Test
  void loadContinuationToken_IOExceptionThrown_shouldThrowRuntimeException() throws IOException {
    // Arrange
    // Create a directory where the token file is expected so reading it causes an IOException
    Path tokenPath = tempDir.resolve("checkpoint").resolve("ns.table").resolve("range1.token");
    Files.createDirectories(tokenPath);

    // Act & Assert
    assertThatThrownBy(() -> manager.loadContinuationToken("ns.table", "range1"))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void persistContinuationToken_shouldWriteToken() throws IOException {
    // Arrange
    manager.initCheckpointFor("ns.table");

    // Act
    manager.persistContinuationToken("ns.table", "range1", "token-abc");

    // Assert
    Path tokenPath = tempDir.resolve("checkpoint").resolve("ns.table").resolve("range1.token");
    assertThat(new String(Files.readAllBytes(tokenPath), StandardCharsets.UTF_8))
        .isEqualTo("token-abc");
  }

  @Test
  void persistContinuationToken_existingTokenGiven_shouldOverwriteToken() throws IOException {
    // Arrange
    Path dir = tempDir.resolve("checkpoint").resolve("ns.table");
    Files.createDirectories(dir);
    Path tokenPath = dir.resolve("range1.token");
    Files.write(tokenPath, "first".getBytes(StandardCharsets.UTF_8));

    // Act
    manager.persistContinuationToken("ns.table", "range1", "second");

    // Assert
    assertThat(new String(Files.readAllBytes(tokenPath), StandardCharsets.UTF_8))
        .isEqualTo("second");
  }

  @Test
  void initCheckpointFor_shouldCreateDirectoryStructure() {
    // Arrange

    // Act
    manager.initCheckpointFor("ns.table");

    // Assert
    Path tableDir = tempDir.resolve("checkpoint").resolve("ns.table");
    assertThat(tableDir).isDirectory();
  }

  @Test
  void persistContinuationToken_shouldNotLeaveTemporaryFile() {
    // Arrange
    manager.initCheckpointFor("ns.table");

    // Act
    manager.persistContinuationToken("ns.table", "range1", "token");

    // Assert
    Path tmpPath = tempDir.resolve("checkpoint").resolve("ns.table").resolve("range1.token.tmp");
    assertThat(tmpPath).doesNotExist();
  }

  @Test
  void persistContinuationToken_shouldStoreTokensIndependentlyPerRangeAndTable()
      throws IOException {
    // Arrange
    manager.initCheckpointFor("ns.table");
    manager.initCheckpointFor("ns.table2");

    // Act
    manager.persistContinuationToken("ns.table", "rangeA", "tokenA");
    manager.persistContinuationToken("ns.table", "rangeB", "tokenB");
    manager.persistContinuationToken("ns.table2", "rangeA", "tokenC");

    // Assert
    Path pathA = tempDir.resolve("checkpoint").resolve("ns.table").resolve("rangeA.token");
    Path pathB = tempDir.resolve("checkpoint").resolve("ns.table").resolve("rangeB.token");
    Path pathC = tempDir.resolve("checkpoint").resolve("ns.table2").resolve("rangeA.token");
    assertThat(new String(Files.readAllBytes(pathA), StandardCharsets.UTF_8)).isEqualTo("tokenA");
    assertThat(new String(Files.readAllBytes(pathB), StandardCharsets.UTF_8)).isEqualTo("tokenB");
    assertThat(new String(Files.readAllBytes(pathC), StandardCharsets.UTF_8)).isEqualTo("tokenC");
  }

  @Test
  void loadFeedRanges_nonExistentFileGiven_shouldReturnNull() {
    // Arrange

    // Act
    String ranges = manager.loadFeedRanges("ns.table");

    // Assert
    assertThat(ranges).isNull();
  }

  @Test
  void loadFeedRanges_existentFileGiven_shouldReturnContent() throws IOException {
    // Arrange
    Path dir = tempDir.resolve("checkpoint").resolve("ns.table");
    Files.createDirectories(dir);
    Files.write(dir.resolve("feed_ranges.json"), "[\"range1\"]".getBytes(StandardCharsets.UTF_8));

    // Act
    String loaded = manager.loadFeedRanges("ns.table");

    // Assert
    assertThat(loaded).isEqualTo("[\"range1\"]");
  }

  @Test
  void persistFeedRanges_shouldWriteFeedRanges() throws IOException {
    // Arrange
    manager.initCheckpointFor("ns.table");

    // Act
    manager.persistFeedRanges("ns.table", "[\"range1\",\"range2\"]");

    // Assert
    Path path = tempDir.resolve("checkpoint").resolve("ns.table").resolve("feed_ranges.json");
    assertThat(new String(Files.readAllBytes(path), StandardCharsets.UTF_8))
        .isEqualTo("[\"range1\",\"range2\"]");
  }

  @Test
  void persistFeedRanges_existingFileGiven_shouldOverwriteContent() throws IOException {
    // Arrange
    Path dir = tempDir.resolve("checkpoint").resolve("ns.table");
    Files.createDirectories(dir);
    Path path = dir.resolve("feed_ranges.json");
    Files.write(path, "[\"first\"]".getBytes(StandardCharsets.UTF_8));

    // Act
    manager.persistFeedRanges("ns.table", "[\"second\"]");

    // Assert
    assertThat(new String(Files.readAllBytes(path), StandardCharsets.UTF_8))
        .isEqualTo("[\"second\"]");
  }

  @Test
  void initCheckpointFor_calledTwice_shouldNotThrowException() {
    // Arrange
    manager.initCheckpointFor("ns.table");

    // Act & Assert
    assertThatCode(() -> manager.initCheckpointFor("ns.table")).doesNotThrowAnyException();
  }

  @Test
  void persistFeedRanges_shouldNotLeaveTemporaryFile() {
    // Arrange
    manager.initCheckpointFor("ns.table");

    // Act
    manager.persistFeedRanges("ns.table", "[]");

    // Assert
    Path tmpPath =
        tempDir.resolve("checkpoint").resolve("ns.table").resolve("feed_ranges.json.tmp");
    assertThat(tmpPath).doesNotExist();
  }

  @Test
  void loadFeedRanges_IOExceptionThrown_shouldThrowRuntimeException() throws IOException {
    // Arrange
    // Create a directory where the feed_ranges.json file is expected so reading it causes an
    // IOException
    Path feedRangesPath =
        tempDir.resolve("checkpoint").resolve("ns.table").resolve("feed_ranges.json");
    Files.createDirectories(feedRangesPath);

    // Act & Assert
    assertThatThrownBy(() -> manager.loadFeedRanges("ns.table"))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void persistContinuationToken_IOExceptionThrown_shouldThrowRuntimeException() {
    // Arrange
    // Use a CheckpointManager pointing to a file (not a directory) so directory creation fails
    CheckpointManager brokenManager =
        new CheckpointManager(tempDir.resolve("checkpoint").resolve("ns.table").resolve("file"));
    try {
      Files.createDirectories(tempDir.resolve("checkpoint").resolve("ns.table"));
      Files.write(
          tempDir.resolve("checkpoint").resolve("ns.table").resolve("file"),
          "block".getBytes(StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    // Act & Assert
    assertThatThrownBy(() -> brokenManager.persistContinuationToken("ns.table", "range1", "token"))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void persistFeedRanges_IOExceptionThrown_shouldThrowRuntimeException() {
    // Arrange
    // Use a CheckpointManager pointing to a file (not a directory) so directory creation fails
    CheckpointManager brokenManager =
        new CheckpointManager(tempDir.resolve("checkpoint").resolve("ns.table").resolve("file"));
    try {
      Files.createDirectories(tempDir.resolve("checkpoint").resolve("ns.table"));
      Files.write(
          tempDir.resolve("checkpoint").resolve("ns.table").resolve("file"),
          "block".getBytes(StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    // Act & Assert
    assertThatThrownBy(() -> brokenManager.persistFeedRanges("ns.table", "[]"))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void clearCheckpointFor_shouldRemoveTableDirectoryButKeepCheckpointDirectory()
      throws IOException {
    // Arrange
    Path dir = tempDir.resolve("checkpoint").resolve("ns.table");
    Files.createDirectories(dir);
    Files.write(dir.resolve("range1.token"), "token".getBytes(StandardCharsets.UTF_8));
    Files.write(dir.resolve("feed_ranges.json"), "[]".getBytes(StandardCharsets.UTF_8));

    // Act
    manager.clearCheckpointFor("ns.table");

    // Assert
    Path checkpointPath = tempDir.resolve("checkpoint");
    assertThat(checkpointPath).exists();
    assertThat(dir).doesNotExist();
  }

  @Test
  void clearCheckpointFor_shouldNotAffectOtherTables() throws IOException {
    // Arrange
    Path tableADir = tempDir.resolve("checkpoint").resolve("ns.tableA");
    Path tableBDir = tempDir.resolve("checkpoint").resolve("ns.tableB");
    Files.createDirectories(tableADir);
    Files.createDirectories(tableBDir);
    Files.write(tableADir.resolve("range1.token"), "tokenA".getBytes(StandardCharsets.UTF_8));
    Files.write(tableBDir.resolve("range1.token"), "tokenB".getBytes(StandardCharsets.UTF_8));

    // Act
    manager.clearCheckpointFor("ns.tableA");

    // Assert
    assertThat(tableADir).doesNotExist();
    assertThat(tableBDir).exists();
    assertThat(
            new String(
                Files.readAllBytes(tableBDir.resolve("range1.token")), StandardCharsets.UTF_8))
        .isEqualTo("tokenB");
  }

  @Test
  void clearCheckpointFor_whenTableDirDoesNotExist_shouldNotThrowException() {
    // Arrange

    // Act & Assert
    assertThatCode(() -> manager.clearCheckpointFor("ns.nonexistent")).doesNotThrowAnyException();
  }
}
