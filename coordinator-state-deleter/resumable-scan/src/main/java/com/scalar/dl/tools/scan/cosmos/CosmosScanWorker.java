package com.scalar.dl.tools.scan.cosmos;

import com.azure.cosmos.CosmosContainer;
import com.azure.cosmos.models.CosmosQueryRequestOptions;
import com.azure.cosmos.models.FeedRange;
import com.azure.cosmos.models.FeedResponse;
import com.azure.cosmos.util.CosmosPagedIterable;
import com.scalar.db.api.Result;
import com.scalar.db.storage.cosmos.Record;
import com.scalar.db.storage.cosmos.ResultInterpreter;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scans a single FeedRange of a Cosmos container.
 *
 * <p>Iterates pages via CosmosPagedIterable, converts each Cosmos document to a ScalarDB Result via
 * {@link ResultInterpreter}, delivers it to the consumer, and checkpoints the continuation token
 * after each page.
 *
 * <p>Does NOT use setMaxDegreeOfParallelism (per design §7.4.2).
 */
class CosmosScanWorker implements Callable<Long> {

  private static final Logger logger = LoggerFactory.getLogger(CosmosScanWorker.class);

  private final CosmosContainer container;
  private final FeedRange feedRange;
  private final String feedRangeId;
  private final String tableName;
  @Nullable private final String continuationToken;
  private final Consumer<Result> recordConsumer;
  private final ResultInterpreter resultInterpreter;
  private final CheckpointManager checkpointManager;

  CosmosScanWorker(
      CosmosContainer container,
      FeedRange feedRange,
      String feedRangeId,
      String tableName,
      @Nullable String continuationToken,
      Consumer<Result> recordConsumer,
      ResultInterpreter resultInterpreter,
      CheckpointManager checkpointManager) {
    this.container = container;
    this.feedRange = feedRange;
    this.feedRangeId = feedRangeId;
    this.tableName = tableName;
    this.continuationToken = continuationToken;
    this.recordConsumer = recordConsumer;
    this.resultInterpreter = resultInterpreter;
    this.checkpointManager = checkpointManager;
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
      pages = iterable.iterableByPage(continuationToken);
    } else {
      pages = iterable.iterableByPage();
    }

    for (FeedResponse<Record> page : pages) {
      if (Thread.interrupted()) {
        throw new InterruptedException("Scan worker interrupted for " + tableName);
      }
      for (Record record : page.getResults()) {
        Result result = resultInterpreter.interpret(record);
        recordConsumer.accept(result);
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
