package com.scalar.dl.tools.scan.cosmos;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import javax.annotation.Nullable;

/**
 * Manages scan-internal checkpoint state: per-partition continuation tokens and FeedRange files.
 *
 * <p>The checkpoint directory is provided by the caller (typically the orchestrator) and should
 * point to a scan-specific subdirectory (e.g., {@code <checkpoint-root>/scan}). Files are stored
 * under {@code <checkpointDir>/<tableName>/} with per-partition token files and a feed_ranges.json.
 *
 * <p><b>Thread safety:</b> Multiple scan workers concurrently write their own .token files. Each
 * .token file is written by exactly one scan worker, so no file locks are needed.
 */
class CheckpointManager {

  private static final String FEED_RANGES_FILE = "feed_ranges.json";

  private final Path checkpointDir;

  public CheckpointManager(Path checkpointDir) {
    this.checkpointDir = checkpointDir;
  }

  @SuppressFBWarnings(
      value = "NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE",
      justification = "target.getFileName() is never null for our use cases (non-root paths)")
  private static void writeAtomic(Path target, byte[] content) throws IOException {
    Path tmp = target.resolveSibling(target.getFileName().toString() + ".tmp");
    try {
      Files.write(tmp, content);
      Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      try {
        Files.deleteIfExists(tmp);
      } catch (IOException suppressed) {
        e.addSuppressed(suppressed);
      }
      throw e;
    }
  }

  /** Load continuation token for a given table and range, or null. */
  @Nullable
  public String loadContinuationToken(String tableName, String rangeId) {
    Path tokenPath = checkpointDir.resolve(tableName).resolve(rangeId + ".token");
    if (!Files.exists(tokenPath)) {
      return null;
    }
    try {
      return new String(Files.readAllBytes(tokenPath), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new RuntimeException(
          "Failed to load continuation token for " + tableName + "/" + rangeId, e);
    }
  }

  /** Atomically persist continuation token for a given table and range. */
  public void persistContinuationToken(String tableName, String rangeId, String token) {
    Path tokenPath = checkpointDir.resolve(tableName).resolve(rangeId + ".token");
    try {
      writeAtomic(tokenPath, token.getBytes(StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new RuntimeException(
          "Failed to persist continuation token for " + tableName + "/" + rangeId, e);
    }
  }

  /** Load persisted feed range strings, or null if none persisted. */
  @Nullable
  public String loadFeedRanges(String tableName) {
    Path feedRangesPath = checkpointDir.resolve(tableName).resolve(FEED_RANGES_FILE);
    if (!Files.exists(feedRangesPath)) {
      return null;
    }
    try {
      return new String(Files.readAllBytes(feedRangesPath), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new RuntimeException("Failed to load feed ranges for " + tableName, e);
    }
  }

  /** Persist feed range strings. Called before spawning scan workers. */
  public void persistFeedRanges(String tableName, String feedRangesJson) {
    Path feedRangesPath = checkpointDir.resolve(tableName).resolve(FEED_RANGES_FILE);
    try {
      writeAtomic(feedRangesPath, feedRangesJson.getBytes(StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new RuntimeException("Failed to persist feed ranges for " + tableName, e);
    }
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
}
