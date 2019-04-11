/*
 * This file is part of the Scalar DL Explorer.
 * Copyright (c) 2019 Scalar, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * You can be released from the requirements of the license by purchasing
 * a commercial license.  For more information, please contact Scalar, Inc.
 */

package com.scalar.client.tool.explorer.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.scalar.ledger.asset.Asset;
import com.scalar.ledger.asset.InternalAsset;
import com.scalar.ledger.database.AssetFilter;
import com.scalar.ledger.exception.ContractContextException;
import com.scalar.ledger.ledger.Ledger;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

public class ScanContractTest {
  @Mock private Ledger ledger;
  private ScanContract scan = new ScanContract();

  @Before
  public void setUp() {
    MockitoAnnotations.initMocks(this);
  }

  private InternalAsset createInternalAsset() {
    return new InternalAsset() {
      @Override
      public JsonObject input() {
        return Json.createObjectBuilder().add("input", "input").build();
      }

      @Override
      public byte[] signature() {
        return "signature".getBytes();
      }

      @Override
      public String contractId() {
        return "contract";
      }

      @Override
      public JsonObject argument() {
        return Json.createObjectBuilder().add("argument", "argument").build();
      }

      @Override
      public byte[] hash() {
        return "hash".getBytes();
      }

      @Override
      public byte[] prevHash() {
        return "prevhash".getBytes();
      }

      @Override
      public String id() {
        return "id";
      }

      @Override
      public int age() {
        return 0;
      }

      @Override
      public JsonObject data() {
        return Json.createObjectBuilder().add("data", "data").build();
      }
    };
  }

  @Test
  public void scanContract_AssetIdNotGiven_ShouldThrowContractContextException() {
    // Act-assert
    assertThatThrownBy(
            () -> scan.invoke(ledger, Json.createObjectBuilder().build(), Optional.empty()))
        .isInstanceOf(ContractContextException.class)
        .hasMessage("asset_id attribute is missing");
  }

  @Test
  public void scanContract_GivenArgument_ShouldTranslateToAssetFilterCorrectly() {
    // Arrange
    ArgumentCaptor<AssetFilter> captor = ArgumentCaptor.forClass(AssetFilter.class);
    JsonObject argument =
        Json.createObjectBuilder()
            .add("asset_id", "asset")
            .add("start", 3)
            .add("end", 6)
            .add("limit", 9)
            .add("ascending", true)
            .build();

    // Act
    scan.invoke(ledger, argument, Optional.empty());

    // Assert
    verify(ledger).scan(captor.capture());
    AssetFilter filter = captor.getValue();
    assertThat(filter.getId()).isEqualTo("asset");
    assertThat(filter.getStartVersion().get()).isEqualTo(3);
    assertThat(filter.getEndVersion().get()).isEqualTo(6);
    assertThat(filter.getLimit()).isEqualTo(9);
    assertThat(filter.getVersionOrder().get()).isEqualTo(AssetFilter.VersionOrder.ASC);
  }

  @Test
  public void scanContract_GivenArgumentWithNoOrder_ShouldBeInDescendingOrder() {
    // Arrange
    ArgumentCaptor<AssetFilter> captor = ArgumentCaptor.forClass(AssetFilter.class);
    JsonObject argument = Json.createObjectBuilder().add("asset_id", "asset").build();

    // Act
    scan.invoke(ledger, argument, Optional.empty());

    // Assert
    verify(ledger).scan(captor.capture());
    AssetFilter filter = captor.getValue();
    assertThat(filter.getId()).isEqualTo("asset");
    assertThat(filter.getVersionOrder().get()).isEqualTo(AssetFilter.VersionOrder.DESC);
  }

  @Test
  public void scanContract_GivenAssetIdWithNoHistory_ShouldReturnEmptyHistory() {
    // Arrange
    JsonObject argument = Json.createObjectBuilder().add("asset_id", "asset").build();

    // Act
    JsonObject result = scan.invoke(ledger, argument, Optional.empty());
    JsonArray history = result.getJsonArray("history");

    // Assert
    assertThat(history.size()).isEqualTo(0);
  }

  @Test
  public void scanContract_GivenAssetIdWithHistory_ShouldReturnHistory() {
    // Arrange
    Base64.Encoder encoder = Base64.getEncoder();
    InternalAsset asset = createInternalAsset();
    List<Asset> history = new ArrayList<>();
    history.add(asset);
    when(ledger.scan(Mockito.any(AssetFilter.class))).thenReturn(history);
    JsonObject argument = Json.createObjectBuilder().add("asset_id", "asset").build();

    // Act
    JsonObject resultHistory = scan.invoke(ledger, argument, Optional.empty());
    JsonObject result = resultHistory.getJsonArray("history").getJsonObject(0);

    // Assert
    assertThat(asset.id()).isEqualTo(result.getString("id"));
    assertThat(asset.age()).isEqualTo(result.getInt("age"));
    assertThat(asset.data()).isEqualTo(result.getJsonObject("data"));
    assertThat(asset.input()).isEqualTo(result.getJsonObject("input"));
    assertThat(asset.contractId()).isEqualTo(result.getString("contract_id"));
    assertThat(asset.argument()).isEqualTo(result.getJsonObject("argument"));
    assertThat(encoder.encodeToString(asset.signature())).isEqualTo(result.getString("signature"));
    assertThat(encoder.encodeToString(asset.prevHash())).isEqualTo(result.getString("prev_hash"));
    assertThat(encoder.encodeToString(asset.hash())).isEqualTo(result.getString("hash"));
  }
}
