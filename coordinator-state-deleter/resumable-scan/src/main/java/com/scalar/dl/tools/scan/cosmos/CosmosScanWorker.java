package com.scalar.dl.tools.scan.cosmos;

import com.azure.cosmos.CosmosContainer;
import com.azure.cosmos.models.CosmosQueryRequestOptions;
import com.azure.cosmos.models.FeedRange;
import com.azure.cosmos.models.FeedResponse;
import com.azure.cosmos.util.CosmosPagedIterable;
import com.scalar.db.api.Result;
import com.scalar.db.storage.cosmos.Record;
import com.scalar.db.storage.cosmos.ResultInterpreter;
import com.scalar.dl.tools.scan.RecordHandler;
import java.util.concurrent.Callable;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scans a single FeedRange of a Cosmos container.
 *
 * <p>Iterates pages via CosmosPagedIterable, converts each Cosmos document to a ScalarDB Result via
 * {@link ResultInterpreter}, delivers it to the {@link RecordHandler}, and checkpoints the
 * continuation token after each page.
 *
 * <p>Does NOT use setMaxDegreeOfParallelism: in that mode the continuation token covers the entire
 * logical scan rather than a single partition, so it cannot be resumed per FeedRange.
 */
class CosmosScanWorker implements Callable<Long> {

  private static final Logger logger = LoggerFactory.getLogger(CosmosScanWorker.class);

  private final CosmosContainer container;
  private final FeedRange feedRange;
  private final String feedRangeId;
  private final String tableName;
  @Nullable private final String continuationToken;
  private final RecordHandler recordHandler;
  private final ResultInterpreter resultInterpreter;
  private final CheckpointManager checkpointManager;
  private final int maxItemCount;

  CosmosScanWorker(
      CosmosContainer container,
      FeedRange feedRange,
      String feedRangeId,
      String tableName,
      @Nullable String continuationToken,
      RecordHandler recordHandler,
      ResultInterpreter resultInterpreter,
      CheckpointManager checkpointManager,
      int maxItemCount) {
    this.container = container;
    this.feedRange = feedRange;
    this.feedRangeId = feedRangeId;
    this.tableName = tableName;
    this.continuationToken = continuationToken;
    this.recordHandler = recordHandler;
    this.resultInterpreter = resultInterpreter;
    this.checkpointManager = checkpointManager;
    this.maxItemCount = maxItemCount;
  }

  @Override
  public Long call() throws Exception {
    long scanned = 0;

    CosmosQueryRequestOptions options = new CosmosQueryRequestOptions();
    options.setFeedRange(feedRange);

    CosmosPagedIterable<Record> iterable =
        container.queryItems("SELECT * FROM c", options, Record.class);

    // Use iterableByPage to get access to continuation tokens for checkpointing
    Iterable<FeedResponse<Record>> pages;
    if (continuationToken != null) {
      // Resume from the last checkpointed continuation token
      pages = iterable.iterableByPage(continuationToken, maxItemCount);
    } else {
      pages = iterable.iterableByPage(maxItemCount);
    }

    for (FeedResponse<Record> page : pages) {
      if (Thread.interrupted()) {
        throw new InterruptedException("Scan worker interrupted for " + tableName);
      }
      for (Record record : page.getResults()) {
        Result result = resultInterpreter.interpret(record);
        recordHandler.handle(result);
        scanned++;
      }

      // Checkpoint after each page
      String nextToken = page.getContinuationToken();
      if (nextToken != null) {
        checkpointManager.persistContinuationToken(tableName, feedRangeId, nextToken);
      }
    }

    logger.debug("FeedRange {} for table {}: scanned {} records", feedRangeId, tableName, scanned);
    return scanned;
  }
}
