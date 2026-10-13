package com.scalar.dl.tools.scan.checkpoint;

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
 * Manages scan-internal checkpoint state: per-table progress tokens keyed by an
 * implementation-defined ID, plus an optional feed-ranges file used by the Cosmos DB scanner.
 *
 * <p>The token format is opaque to this class: the Cosmos DB scanner stores one continuation token
 * per FeedRange (the FeedRange ID is the token ID), while other scanner implementations may store a
 * single token under a fixed ID.
 *
 * <p>The checkpoint directory is provided by the caller (typically the orchestrator) and should
 * point to a scan-specific subdirectory (e.g., {@code <checkpoint-root>/scan}). Files are stored
 * under {@code <checkpointDir>/<tableName>/} with per-ID token files and, for Cosmos DB, a
 * feed_ranges.json.
 *
 * <p><b>Thread safety:</b> Multiple scan workers concurrently write their own .token files. Each
 * .token file is written by exactly one scan worker, so no file locks are needed.
 */
public class CheckpointManager {

  private static final String FEED_RANGES_FILE = "feed_ranges.json";

  private final Path checkpointDir;

  public CheckpointManager(Path checkpointDir) {
    this.checkpointDir = checkpointDir;
  }

  /** Load the progress token for a given table and token ID, or null. */
  @Nullable
  public String loadToken(String tableName, String tokenId) {
    Path tokenPath = checkpointDir.resolve(tableName).resolve(tokenId + ".token");
    if (!Files.exists(tokenPath)) {
      return null;
    }
    try {
      return new String(Files.readAllBytes(tokenPath), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new RuntimeException("Failed to load token for " + tableName + "/" + tokenId, e);
    }
  }

  /** Atomically persist the progress token for a given table and token ID. */
  public void persistToken(String tableName, String tokenId, String token) {
    Path tokenPath = checkpointDir.resolve(tableName).resolve(tokenId + ".token");
    try {
      FileUtils.writeAtomic(tokenPath, token.getBytes(StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new RuntimeException("Failed to persist token for " + tableName + "/" + tokenId, e);
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
      FileUtils.writeAtomic(feedRangesPath, feedRangesJson.getBytes(StandardCharsets.UTF_8));
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
