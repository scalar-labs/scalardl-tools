package com.scalar.dl.tools.scan;

import com.scalar.dl.tools.common.FileUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import javax.annotation.Nullable;

/**
 * Base class of the scanner-specific checkpoint managers, which store scan-internal checkpoint
 * state in files.
 *
 * <p>The checkpoint directory is provided by the caller (typically the orchestrator) and should
 * point to a scan-specific subdirectory (e.g., {@code <checkpoint-root>/scan}). The checkpoint
 * files of a table are stored under {@code <checkpointDir>/<tableName>/}, and each file is written
 * atomically.
 */
public abstract class AbstractCheckpointManager {

  private final Path checkpointDir;

  protected AbstractCheckpointManager(Path checkpointDir) {
    this.checkpointDir = checkpointDir;
  }

  /** Create the checkpoint directory for the given table. Called before scanning. */
  public void initCheckpointFor(String tableName) {
    Path tableDir = checkpointDir.resolve(tableName);
    try {
      Files.createDirectories(tableDir);
    } catch (IOException e) {
      throw new RuntimeException(
          "Failed to create checkpoint directory for " + tableName + " in " + checkpointDir, e);
    }
  }

  /** Delete checkpoint files for the given table, keeping the checkpoint directory itself. */
  public void clearCheckpointFor(String tableName) {
    Path tableDir = checkpointDir.resolve(tableName);
    if (!Files.exists(tableDir)) {
      return;
    }
    try {
      Files.walkFileTree(
          tableDir,
          new SimpleFileVisitor<Path>() {
            @Override
            @NonNull
            public FileVisitResult visitFile(@NonNull Path file, @NonNull BasicFileAttributes attrs)
                throws IOException {
              Files.delete(file);
              return FileVisitResult.CONTINUE;
            }

            @Override
            @NonNull
            public FileVisitResult postVisitDirectory(@NonNull Path dir, IOException exc)
                throws IOException {
              Files.delete(dir);
              return FileVisitResult.CONTINUE;
            }
          });
    } catch (IOException e) {
      throw new RuntimeException(
          "Failed to clear checkpoint for table " + tableName + " in " + checkpointDir, e);
    }
  }

  /** Load the content of the given checkpoint file of a table, or null if it does not exist. */
  @Nullable
  protected String load(String tableName, String fileName) {
    Path path = checkpointDir.resolve(tableName).resolve(fileName);
    if (!Files.exists(path)) {
      return null;
    }
    try {
      return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new RuntimeException("Failed to load checkpoint file " + path, e);
    }
  }

  /** Atomically persist the content of the given checkpoint file of a table. */
  protected void persist(String tableName, String fileName, String content) {
    Path path = checkpointDir.resolve(tableName).resolve(fileName);
    try {
      FileUtils.writeAtomic(path, content.getBytes(StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new RuntimeException("Failed to persist checkpoint file " + path, e);
    }
  }
}
