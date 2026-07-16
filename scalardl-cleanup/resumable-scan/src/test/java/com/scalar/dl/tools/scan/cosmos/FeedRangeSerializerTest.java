package com.scalar.dl.tools.scan.cosmos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.azure.cosmos.models.FeedRange;
import com.azure.cosmos.models.PartitionKey;
import org.junit.jupiter.api.Test;

class FeedRangeSerializerTest {

  // Use the SDK's factory methods to create valid FeedRange instances
  private static final FeedRange RANGE_1 = FeedRange.forFullRange();
  private static final FeedRange RANGE_2 = FeedRange.forLogicalPartition(new PartitionKey("key1"));

  @Test
  void toId_shouldProduceFixedLengthHexString() {
    // Arrange

    // Act
    String id = FeedRangeSerializer.toId(RANGE_1);

    // Assert
    assertThat(id).hasSize(64).matches("[0-9a-f]+");
  }

  @Test
  void toId_sameFeedRangeGiven_shouldReturnSameId() {
    // Arrange

    // Act
    String id1 = FeedRangeSerializer.toId(RANGE_1);
    String id2 = FeedRangeSerializer.toId(RANGE_1);

    // Assert
    assertThat(id1).isEqualTo(id2);
  }

  @Test
  void toId_differentFeedRangesGiven_shouldReturnDifferentIds() {
    // Arrange

    // Act
    String id1 = FeedRangeSerializer.toId(RANGE_1);
    String id2 = FeedRangeSerializer.toId(RANGE_2);

    // Assert
    assertThat(id1).isNotEqualTo(id2);
  }

  @Test
  void toJson_shouldReturnNonEmptyString() {
    // Arrange

    // Act
    String json = FeedRangeSerializer.toJson(RANGE_1);

    // Assert
    assertThat(json).isNotEmpty();
  }

  @Test
  void fromJson_validJsonGiven_shouldRestoreOriginalFeedRange() {
    // Arrange
    String json = FeedRangeSerializer.toJson(RANGE_1);

    // Act
    FeedRange restored = FeedRangeSerializer.fromJson(json);

    // Assert
    assertThat(FeedRangeSerializer.toJson(restored)).isEqualTo(json);
  }

  @Test
  void fromJson_invalidJsonGiven_shouldThrowException() {
    // Arrange

    // Act & Assert
    assertThatThrownBy(() -> FeedRangeSerializer.fromJson("not-valid-json"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
