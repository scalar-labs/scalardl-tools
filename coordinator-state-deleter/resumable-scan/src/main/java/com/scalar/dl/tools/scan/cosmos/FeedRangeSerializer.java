package com.scalar.dl.tools.scan.cosmos;

import com.azure.cosmos.models.FeedRange;
import com.google.common.hash.Hashing;
import java.nio.charset.StandardCharsets;

/**
 * Serializes FeedRange to a stable string ID for use in file names and checkpoint keys.
 *
 * <p>FeedRange.toString() returns a JSON string like {"Range":{"min":"","max":"FF"}}. We need a
 * filesystem-safe identifier derived from this for .token file names.
 */
class FeedRangeSerializer {

  private FeedRangeSerializer() {}

  /** Convert a FeedRange to a stable, filesystem-safe identifier. */
  public static String toId(FeedRange feedRange) {
    // Use a hash to produce a fixed-length filesystem-safe identifier
    return Hashing.sha256().hashString(toJson(feedRange), StandardCharsets.UTF_8).toString();
  }

  /** Convert a FeedRange to its JSON string for persistence. */
  public static String toJson(FeedRange feedRange) {
    return feedRange.toString();
  }

  /** Reconstruct a FeedRange from its persisted JSON string. */
  public static FeedRange fromJson(String json) {
    return FeedRange.fromString(json);
  }
}
