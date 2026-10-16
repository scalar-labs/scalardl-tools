package com.scalar.dl.tools.scan;

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

class AbstractCheckpointManagerTest {

  private static final class TestCheckpointManager extends AbstractCheckpointManager {
    private TestCheckpointManager(Path checkpointDir) {
      super(checkpointDir);
    }
  }

  @TempDir Path tempDir;
  private Path checkpointDir;
  private TestCheckpointManager manager;

  @BeforeEach
  void setUp() {
    checkpointDir = tempDir.resolve("checkpoint");
    manager = new TestCheckpointManager(checkpointDir);
  }

  @Test
  void initCheckpointFor_shouldCreateTableDirectory() {
    // Act
    manager.initCheckpointFor("ns.table");

    // Assert
    assertThat(checkpointDir.resolve("ns.table")).isDirectory();
  }

  @Test
  void initCheckpointFor_whenCalledTwice_shouldNotThrowException() {
    // Arrange
    manager.initCheckpointFor("ns.table");

    // Act & Assert
    assertThatCode(() -> manager.initCheckpointFor("ns.table")).doesNotThrowAnyException();
  }

  @Test
  void persist_shouldWriteContentLoadableByLoad() {
    // Arrange
    manager.initCheckpointFor("ns.table");

    // Act
    manager.persist("ns.table", "file", "first");
    manager.persist("ns.table", "file", "second");

    // Assert
    assertThat(manager.load("ns.table", "file")).isEqualTo("second");
    assertThat(checkpointDir.resolve("ns.table").resolve("file")).exists();
    assertThat(checkpointDir.resolve("ns.table").resolve("file.tmp")).doesNotExist();
  }

  @Test
  void persist_whenWriteFails_shouldThrowRuntimeException() throws IOException {
    // Arrange
    // A regular file where the table directory is expected makes the write fail
    Files.createDirectories(checkpointDir);
    Files.write(checkpointDir.resolve("ns.table"), "block".getBytes(StandardCharsets.UTF_8));

    // Act & Assert
    assertThatThrownBy(() -> manager.persist("ns.table", "file", "content"))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void load_nonExistentFileGiven_shouldReturnNull() {
    assertThat(manager.load("ns.table", "file")).isNull();
  }

  @Test
  void load_whenOnlyTmpFilePresent_shouldReturnNull() throws IOException {
    // Arrange
    // Simulate a crash that left only the .tmp file
    Path tableDir = checkpointDir.resolve("ns.table");
    Files.createDirectories(tableDir);
    Files.write(tableDir.resolve("file.tmp"), "incomplete".getBytes(StandardCharsets.UTF_8));

    // Act & Assert
    assertThat(manager.load("ns.table", "file")).isNull();
  }

  @Test
  void load_whenReadFails_shouldThrowRuntimeException() throws IOException {
    // Arrange
    // A directory where the file is expected makes the read fail
    Files.createDirectories(checkpointDir.resolve("ns.table").resolve("file"));

    // Act & Assert
    assertThatThrownBy(() -> manager.load("ns.table", "file")).isInstanceOf(RuntimeException.class);
  }

  @Test
  void clearCheckpointFor_shouldDeleteTableDirectoryOnly() throws IOException {
    // Arrange
    manager.initCheckpointFor("ns.table");
    manager.initCheckpointFor("ns.other");
    manager.persist("ns.table", "file", "content");
    manager.persist("ns.other", "file", "other");

    // Act
    manager.clearCheckpointFor("ns.table");

    // Assert
    assertThat(checkpointDir.resolve("ns.table")).doesNotExist();
    assertThat(checkpointDir).isDirectory();
    assertThat(manager.load("ns.other", "file")).isEqualTo("other");
  }

  @Test
  void clearCheckpointFor_nonExistentDirectoryGiven_shouldDoNothing() {
    assertThatCode(() -> manager.clearCheckpointFor("ns.table")).doesNotThrowAnyException();
  }
}
