package com.scalar.dl.tools.scan.cosmos;

import com.scalar.dl.tools.scan.AbstractCheckpointManager;
import java.nio.file.Path;
import javax.annotation.Nullable;

/**
 * Manages scan-internal checkpoint state: per-partition continuation tokens and FeedRange files.
 *
 * <p>Files are stored under {@code <checkpointDir>/<tableName>/} with per-partition token files and
 * a feed_ranges.json.
 *
 * <p><b>Thread safety:</b> Multiple scan workers concurrently write their own .token files. Each
 * .token file is written by exactly one scan worker, so no file locks are needed.
 */
class CheckpointManager extends AbstractCheckpointManager {

  private static final String FEED_RANGES_FILE = "feed_ranges.json";
  private static final String TOKEN_FILE_SUFFIX = ".token";

  public CheckpointManager(Path checkpointDir) {
    super(checkpointDir);
  }

  /** Load continuation token for a given table and range, or null. */
  @Nullable
  public String loadContinuationToken(String tableName, String rangeId) {
    return load(tableName, rangeId + TOKEN_FILE_SUFFIX);
  }

  /** Atomically persist continuation token for a given table and range. */
  public void persistContinuationToken(String tableName, String rangeId, String token) {
    persist(tableName, rangeId + TOKEN_FILE_SUFFIX, token);
  }

  /** Load persisted feed range strings, or null if none persisted. */
  @Nullable
  public String loadFeedRanges(String tableName) {
    return load(tableName, FEED_RANGES_FILE);
  }

  /** Persist feed range strings. Called before spawning scan workers. */
  public void persistFeedRanges(String tableName, String feedRangesJson) {
    persist(tableName, FEED_RANGES_FILE, feedRangesJson);
  }
}
