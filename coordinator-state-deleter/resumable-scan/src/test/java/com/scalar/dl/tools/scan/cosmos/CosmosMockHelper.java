package com.scalar.dl.tools.scan.cosmos;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.azure.cosmos.CosmosContainer;
import com.azure.cosmos.models.FeedRange;
import com.azure.cosmos.models.FeedResponse;
import com.azure.cosmos.util.CosmosPagedIterable;
import com.scalar.db.api.Result;
import com.scalar.db.storage.cosmos.Record;
import com.scalar.db.storage.cosmos.ResultInterpreter;
import java.util.ArrayList;
import java.util.List;

/** Shared mock construction helpers for Cosmos SDK objects used in scan tests. */
final class CosmosMockHelper {

  private CosmosMockHelper() {}

  /** Create a mock FeedResponse with the given records and continuation token. */
  @SuppressWarnings("unchecked")
  static FeedResponse<Record> createMockPage(List<Record> records, String continuationToken) {
    FeedResponse<Record> page = mock(FeedResponse.class);
    when(page.getResults()).thenReturn(records);
    when(page.getContinuationToken()).thenReturn(continuationToken);
    return page;
  }

  /** Create a mock CosmosPagedIterable that returns the given pages via iterableByPage(). */
  @SuppressWarnings("unchecked")
  static CosmosPagedIterable<Record> createMockPagedIterable(List<FeedResponse<Record>> pages) {
    CosmosPagedIterable<Record> iterable = mock(CosmosPagedIterable.class);
    when(iterable.iterableByPage()).thenReturn(pages);
    return iterable;
  }

  /**
   * Create a mock CosmosPagedIterable that returns different pages depending on whether a
   * continuation token is provided for resume.
   */
  @SuppressWarnings("unchecked")
  static CosmosPagedIterable<Record> createMockPagedIterableWithResume(
      List<FeedResponse<Record>> pages, List<FeedResponse<Record>> resumePages) {
    CosmosPagedIterable<Record> iterable = mock(CosmosPagedIterable.class);
    when(iterable.iterableByPage()).thenReturn(pages);
    when(iterable.iterableByPage(anyString())).thenReturn(resumePages);
    return iterable;
  }

  /** Create a mock CosmosContainer that returns the given iterable and feed ranges. */
  static CosmosContainer createMockContainer(
      CosmosPagedIterable<Record> pagedIterable, List<FeedRange> feedRanges) {
    CosmosContainer container = mock(CosmosContainer.class);
    when(container.queryItems(anyString(), any(), eq(Record.class))).thenReturn(pagedIterable);
    when(container.getFeedRanges()).thenReturn(feedRanges);
    return container;
  }

  /** Create a mock ResultInterpreter that returns a mock Result for any Record input. */
  static ResultInterpreter createMockResultInterpreter() {
    ResultInterpreter interpreter = mock(ResultInterpreter.class);
    when(interpreter.interpret(any(Record.class))).thenAnswer(invocation -> mock(Result.class));
    return interpreter;
  }

  /** Create a list of mock Record instances. */
  static List<Record> createMockRecords(int count) {
    List<Record> records = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      records.add(mock(Record.class));
    }
    return records;
  }
}
