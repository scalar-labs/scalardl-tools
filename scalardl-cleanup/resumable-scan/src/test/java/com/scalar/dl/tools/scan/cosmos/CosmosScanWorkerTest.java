package com.scalar.dl.tools.scan.cosmos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.azure.cosmos.CosmosContainer;
import com.azure.cosmos.models.FeedRange;
import com.azure.cosmos.models.FeedResponse;
import com.azure.cosmos.util.CosmosPagedIterable;
import com.scalar.db.api.Result;
import com.scalar.db.storage.cosmos.Record;
import com.scalar.db.storage.cosmos.ResultInterpreter;
import com.scalar.dl.tools.scan.RecordHandler;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CosmosScanWorkerTest {

  private static final String TABLE_NAME = "ns.table";
  private static final String RANGE_ID = "range1";
  private static final int DEFAULT_MAX_ITEM_COUNT = 100;

  private final RecordHandler recordHandler = mock(RecordHandler.class);

  private CosmosContainer container;
  private FeedRange feedRange;
  private CheckpointManager checkpointManager;
  private ResultInterpreter resultInterpreter;

  @BeforeEach
  void setUp() {
    feedRange = mock(FeedRange.class);
    checkpointManager = mock(CheckpointManager.class);
    resultInterpreter = CosmosMockHelper.createMockResultInterpreter();
  }

  private CosmosScanWorker createWorker(String continuationToken) {
    return createWorker(continuationToken, DEFAULT_MAX_ITEM_COUNT);
  }

  private CosmosScanWorker createWorker(String continuationToken, int maxItemCount) {
    return new CosmosScanWorker(
        container,
        feedRange,
        RANGE_ID,
        TABLE_NAME,
        continuationToken,
        recordHandler,
        resultInterpreter,
        checkpointManager,
        maxItemCount);
  }

  @Test
  void call_singlePageWithRecords_shouldProcessAllRecords() throws Exception {
    // Arrange
    List<Record> records = CosmosMockHelper.createMockRecords(3);
    FeedResponse<Record> page = CosmosMockHelper.createMockPage(records, null);
    CosmosPagedIterable<Record> iterable =
        CosmosMockHelper.createMockPagedIterable(Collections.singletonList(page));
    container = CosmosMockHelper.createMockContainer(iterable, Collections.emptyList());

    // Act
    long count = createWorker(null).call();

    // Assert
    assertThat(count).isEqualTo(3);
    verify(recordHandler, times(3)).handle(any(Result.class));
  }

  @Test
  void call_multiplePages_shouldProcessAllRecords() throws Exception {
    // Arrange
    List<Record> page1Records = CosmosMockHelper.createMockRecords(2);
    List<Record> page2Records = CosmosMockHelper.createMockRecords(1);
    FeedResponse<Record> page1 = CosmosMockHelper.createMockPage(page1Records, "token-page1");
    FeedResponse<Record> page2 = CosmosMockHelper.createMockPage(page2Records, null);
    CosmosPagedIterable<Record> iterable =
        CosmosMockHelper.createMockPagedIterable(Arrays.asList(page1, page2));
    container = CosmosMockHelper.createMockContainer(iterable, Collections.emptyList());

    // Act
    long count = createWorker(null).call();

    // Assert
    assertThat(count).isEqualTo(3);
  }

  @Test
  void call_emptyResult_shouldReturnZeroAndNotCallHandler() throws Exception {
    // Arrange
    FeedResponse<Record> page = CosmosMockHelper.createMockPage(Collections.emptyList(), null);
    CosmosPagedIterable<Record> iterable =
        CosmosMockHelper.createMockPagedIterable(Collections.singletonList(page));
    container = CosmosMockHelper.createMockContainer(iterable, Collections.emptyList());

    // Act
    long count = createWorker(null).call();

    // Assert
    assertThat(count).isEqualTo(0);
    verify(recordHandler, never()).handle(any());
  }

  @Test
  void call_multiplePagesWithTokens_shouldCheckpointAfterProcessingEachPage() throws Exception {
    // Arrange
    List<Record> records1 = CosmosMockHelper.createMockRecords(1);
    List<Record> records2 = CosmosMockHelper.createMockRecords(1);
    FeedResponse<Record> page1 = CosmosMockHelper.createMockPage(records1, "token-1");
    FeedResponse<Record> page2 = CosmosMockHelper.createMockPage(records2, "token-2");
    CosmosPagedIterable<Record> iterable =
        CosmosMockHelper.createMockPagedIterable(Arrays.asList(page1, page2));
    container = CosmosMockHelper.createMockContainer(iterable, Collections.emptyList());

    // Act
    createWorker(null).call();

    // Assert
    verify(checkpointManager).persistContinuationToken(TABLE_NAME, RANGE_ID, "token-1");
    verify(checkpointManager).persistContinuationToken(TABLE_NAME, RANGE_ID, "token-2");
  }

  @Test
  void call_pageWithNullContinuationToken_shouldSkipCheckpoint() throws Exception {
    // Arrange
    List<Record> records = CosmosMockHelper.createMockRecords(1);
    FeedResponse<Record> page = CosmosMockHelper.createMockPage(records, null);
    CosmosPagedIterable<Record> iterable =
        CosmosMockHelper.createMockPagedIterable(Collections.singletonList(page));
    container = CosmosMockHelper.createMockContainer(iterable, Collections.emptyList());

    // Act
    createWorker(null).call();

    // Assert
    verify(checkpointManager, never())
        .persistContinuationToken(anyString(), anyString(), anyString());
  }

  @Test
  void call_withContinuationToken_shouldResumeFromToken() throws Exception {
    // Arrange
    List<Record> records = CosmosMockHelper.createMockRecords(1);
    FeedResponse<Record> page = CosmosMockHelper.createMockPage(records, null);
    List<FeedResponse<Record>> resumePages = Collections.singletonList(page);
    CosmosPagedIterable<Record> iterable =
        CosmosMockHelper.createMockPagedIterableWithResume(Collections.emptyList(), resumePages);
    container = CosmosMockHelper.createMockContainer(iterable, Collections.emptyList());

    // Act
    long count = createWorker("resume-token").call();

    // Assert
    assertThat(count).isEqualTo(1);
    verify(iterable).iterableByPage("resume-token", DEFAULT_MAX_ITEM_COUNT);
    verify(iterable, never()).iterableByPage(anyInt());
  }

  @Test
  void call_withNullContinuationToken_shouldStartFromBeginning() throws Exception {
    // Arrange
    FeedResponse<Record> page = CosmosMockHelper.createMockPage(Collections.emptyList(), null);
    CosmosPagedIterable<Record> iterable =
        CosmosMockHelper.createMockPagedIterable(Collections.singletonList(page));
    container = CosmosMockHelper.createMockContainer(iterable, Collections.emptyList());

    // Act
    createWorker(null).call();

    // Assert
    verify(iterable).iterableByPage(DEFAULT_MAX_ITEM_COUNT);
    verify(iterable, never()).iterableByPage(anyString(), anyInt());
  }

  @Test
  void call_shouldPassInterpretedResultsToHandler() throws Exception {
    // Arrange
    Result mockResult = mock(Result.class);
    when(resultInterpreter.interpret(any(Record.class))).thenReturn(mockResult);

    List<Record> records = CosmosMockHelper.createMockRecords(1);
    FeedResponse<Record> page = CosmosMockHelper.createMockPage(records, null);
    CosmosPagedIterable<Record> iterable =
        CosmosMockHelper.createMockPagedIterable(Collections.singletonList(page));
    container = CosmosMockHelper.createMockContainer(iterable, Collections.emptyList());

    // Act
    createWorker(null).call();

    // Assert
    verify(recordHandler).handle(mockResult);
  }

  @Test
  void call_withCustomMaxItemCount_shouldPassMaxItemCountToIterableByPage() throws Exception {
    // Arrange
    int maxItemCount = 50;
    FeedResponse<Record> page = CosmosMockHelper.createMockPage(Collections.emptyList(), null);
    CosmosPagedIterable<Record> iterable =
        CosmosMockHelper.createMockPagedIterable(Collections.singletonList(page));
    container = CosmosMockHelper.createMockContainer(iterable, Collections.emptyList());

    // Act
    createWorker(null, maxItemCount).call();

    // Assert
    verify(iterable).iterableByPage(maxItemCount);
  }

  @Test
  void call_handlerThrowsException_shouldPropagateException() throws Exception {
    // Arrange
    List<Record> records = CosmosMockHelper.createMockRecords(1);
    FeedResponse<Record> page = CosmosMockHelper.createMockPage(records, null);
    CosmosPagedIterable<Record> iterable =
        CosmosMockHelper.createMockPagedIterable(Collections.singletonList(page));
    container = CosmosMockHelper.createMockContainer(iterable, Collections.emptyList());

    doThrow(new RuntimeException("handler error")).when(recordHandler).handle(any(Result.class));

    // Act & Assert
    assertThatThrownBy(() -> createWorker(null).call())
        .isInstanceOf(RuntimeException.class)
        .hasMessage("handler error");
  }
}
