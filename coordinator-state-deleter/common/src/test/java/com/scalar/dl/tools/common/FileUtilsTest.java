package com.scalar.dl.tools.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileUtilsTest {

  @TempDir Path tempDir;

  @Test
  void writeAtomic_nonExistentFileGiven_shouldCreateNewFile() throws IOException {
    // Arrange
    Path target = tempDir.resolve("test.json");
    byte[] content = "{\"key\":\"value\"}".getBytes(StandardCharsets.UTF_8);

    // Act
    FileUtils.writeAtomic(target, content);

    // Assert
    assertThat(target).exists();
    assertThat(Files.readAllBytes(target)).isEqualTo(content);
  }

  @Test
  void writeAtomic_existentFileGiven_shouldOverwriteExistingFile() throws IOException {
    // Arrange
    Path target = tempDir.resolve("test.json");
    Files.write(target, "old".getBytes(StandardCharsets.UTF_8));
    byte[] newContent = "new".getBytes(StandardCharsets.UTF_8);

    // Act
    FileUtils.writeAtomic(target, newContent);

    // Assert
    assertThat(Files.readAllBytes(target)).isEqualTo(newContent);
  }

  @Test
  void writeAtomic_shouldNotLeaveTmpFile() throws IOException {
    // Arrange
    Path target = tempDir.resolve("test.json");

    // Act
    FileUtils.writeAtomic(target, "data".getBytes(StandardCharsets.UTF_8));

    // Assert
    assertThat(tempDir.resolve("test.json.tmp")).doesNotExist();
  }

  @Test
  void writeAtomic_fileWithNonExistentParentGiven_shouldThrowIOException() {
    // Arrange
    Path target = tempDir.resolve("no-such-dir").resolve("test.json");

    // Act & Assert
    assertThatThrownBy(() -> FileUtils.writeAtomic(target, "data".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IOException.class);
  }
}
