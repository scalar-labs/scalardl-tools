/*
 * This file is part of the Scalar DL Emulator.
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
 * a commercial license. For more information, please contact Scalar, Inc.
 */
package com.scalar.client.tool.emulator.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scalar.dl.ledger.database.AssetLedger;
import com.scalar.dl.ledger.database.Ledger;
import com.scalar.dl.ledger.database.TamperEvidentAssetbase;
import com.scalar.dl.ledger.emulator.AssetbaseEmulator;
import com.scalar.dl.ledger.exception.ContractContextException;
import java.util.Optional;
import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;
import org.junit.Before;
import org.junit.Test;

public class ScanContractTest {
  private static final String ASSET_ID = "X";
  private static final String WRONG_ASSET_ID = "Y";
  private PutContract put = new PutContract();
  private ScanContract scan = new ScanContract();
  private TamperEvidentAssetbase assetbase = new AssetbaseEmulator();
  private Ledger ledger = new AssetLedger(assetbase);

  private void putAnAgeTwoAssetInTheLedger() {
    JsonObject argument =
        Json.createObjectBuilder()
            .add("asset_id", ASSET_ID)
            .add("data", Json.createObjectBuilder().build())
            .build();
    put.invoke(ledger, argument, Optional.empty());
    assetbase.commit();
    put.invoke(ledger, argument, Optional.empty());
    assetbase.commit();
    put.invoke(ledger, argument, Optional.empty());
    assetbase.commit();
  }

  @Before
  public void setUp() {
    putAnAgeTwoAssetInTheLedger();
  }

  @Test
  public void invoke_AssetExistsAndIdGiven_ShouldResultInSuccessWithAsset() {
    // Arrange
    JsonObject argument = Json.createObjectBuilder().add("asset_id", ASSET_ID).build();

    // Act
    JsonObject result = scan.invoke(ledger, argument, Optional.empty());
    JsonArray data = result.getJsonArray("data");

    // Assert
    assertThat(data.size()).isEqualTo(3);
    assertThat(data.getJsonObject(0).getString("asset_id")).isEqualTo(ASSET_ID);
  }

  @Test
  public void invoke_AssetDoesNotExist_ShouldResultInSuccessWithEmptyList() {
    // Arrange
    JsonObject argument = Json.createObjectBuilder().add("asset_id", WRONG_ASSET_ID).build();

    // Act
    JsonObject result = scan.invoke(ledger, argument, Optional.empty());
    JsonArray data = result.getJsonArray("data");

    // Assert
    assertThat(data.size()).isEqualTo(0);
  }

  @Test
  public void run_AssetIdNotGiven_ShouldThrowContractContextException() {
    // Arrange
    JsonObject argument = Json.createObjectBuilder().build();

    // Act
    assertThatThrownBy(() -> scan.invoke(ledger, argument, Optional.empty()))
        .isInstanceOf(ContractContextException.class);
  }

  @Test
  public void invoke_GivenStartVersion_ShouldReturnCorrectData() {
    // Arrange
    JsonObject argument =
        Json.createObjectBuilder().add("asset_id", ASSET_ID).add("start", 1).build();

    // Act
    JsonObject result = scan.invoke(ledger, argument, Optional.empty());
    JsonArray data = result.getJsonArray("data");

    // Assert
    assertThat(data.size()).isEqualTo(2);
    assertThat(data.getJsonObject(0).getString("asset_id")).isEqualTo(ASSET_ID);
    assertThat(data.getJsonObject(0).getInt("age")).isEqualTo(2);
  }

  @Test
  public void invoke_GivenStartVersionTooLarge_ShouldReturnEmptyData() {
    // Arrange
    JsonObject argument =
        Json.createObjectBuilder().add("asset_id", ASSET_ID).add("start", 5).build();

    // Act
    JsonObject result = scan.invoke(ledger, argument, Optional.empty());
    JsonArray data = result.getJsonArray("data");

    // Assert
    assertThat(data.size()).isEqualTo(0);
  }

  @Test
  public void invoke_GivenNegativeStartVersion_ShouldThrowContractContextException() {
    // Arrange
    JsonObject argument =
        Json.createObjectBuilder().add("asset_id", ASSET_ID).add("start", -2).build();

    // Act-assert
    assertThatThrownBy(() -> scan.invoke(ledger, argument, Optional.empty()))
        .isInstanceOf(ContractContextException.class);
  }

  @Test
  public void invoke_GivenMalformedStartVersion_ShouldResultInClassCastException() {
    // Arrange
    JsonObject argument =
        Json.createObjectBuilder().add("asset_id", ASSET_ID).add("start", "&&").build();

    // Act-asset
    assertThatThrownBy(() -> scan.invoke(ledger, argument, Optional.empty()))
        .isInstanceOf(ClassCastException.class);
  }

  @Test
  public void invoke_GivenEndVersion_ShouldReturnCorrectData() {
    // Arrange
    JsonObject argument =
        Json.createObjectBuilder().add("asset_id", ASSET_ID).add("end", 2).build();

    // Act
    JsonObject result = scan.invoke(ledger, argument, Optional.empty());
    JsonArray data = result.getJsonArray("data");

    // Assert
    assertThat(data.size()).isEqualTo(2);
    assertThat(data.getJsonObject(0).getString("asset_id")).isEqualTo(ASSET_ID);
    assertThat(data.getJsonObject(0).getInt("age")).isEqualTo(1);
  }

  @Test
  public void invoke_GivenNegativeEndVersion_ShouldThrowContractContextException() {
    // Arrange
    JsonObject argument =
        Json.createObjectBuilder().add("asset_id", ASSET_ID).add("end", -2).build();

    // Act-assert
    assertThatThrownBy(() -> scan.invoke(ledger, argument, Optional.empty()))
        .isInstanceOf(ContractContextException.class);
  }

  @Test
  public void invoke_GivenMalformedEndVersion_ShouldResultInClassCastException() {
    // Arrange
    JsonObject argument =
        Json.createObjectBuilder().add("asset_id", ASSET_ID).add("end", "&&").build();

    // Act
    assertThatThrownBy(() -> scan.invoke(ledger, argument, Optional.empty()))
        .isInstanceOf(ClassCastException.class);
  }

  @Test
  public void invoke_GivenStartAndEndVersion_ShouldReturnCorrectData() {
    // Arrange
    JsonObject argument =
        Json.createObjectBuilder().add("asset_id", ASSET_ID).add("start", 1).add("end", 2).build();

    // Act
    JsonObject result = scan.invoke(ledger, argument, Optional.empty());
    JsonArray data = result.getJsonArray("data");

    // Assert
    assertThat(data.size()).isEqualTo(1);
    assertThat(data.getJsonObject(0).getString("asset_id")).isEqualTo(ASSET_ID);
    assertThat(data.getJsonObject(0).getInt("age")).isEqualTo(1);
  }

  @Test
  public void invoke_GivenProperLimit_ShouldReturnCorrectData() {
    // Arrange
    JsonObject argument =
        Json.createObjectBuilder().add("asset_id", ASSET_ID).add("limit", 1).build();

    // Act
    JsonObject result = scan.invoke(ledger, argument, Optional.empty());
    JsonArray data = result.getJsonArray("data");

    // Assert
    assertThat(data.size()).isEqualTo(1);
    assertThat(data.getJsonObject(0).getString("asset_id")).isEqualTo(ASSET_ID);
    assertThat(data.getJsonObject(0).getInt("age")).isEqualTo(2);
  }

  @Test
  public void invoke_GivenNegativeLimit_ShouldThrowContractContextException() {
    // Arrange
    JsonObject argument =
        Json.createObjectBuilder().add("asset_id", ASSET_ID).add("limit", -5).build();

    // Act-assert
    assertThatThrownBy(() -> scan.invoke(ledger, argument, Optional.empty()))
        .isInstanceOf(ContractContextException.class);
  }

  @Test
  public void invoke_GivenMalformedLimit_ShouldResultInClassCastException() {
    // Arrange
    JsonObject argument =
        Json.createObjectBuilder().add("asset_id", ASSET_ID).add("limit", "&&").build();

    // Act
    assertThatThrownBy(() -> scan.invoke(ledger, argument, Optional.empty()))
        .isInstanceOf(ClassCastException.class);
  }

  @Test
  public void invoke_GivenAscOrdering_ShouldResultCorrectData() {
    // Arrange
    JsonObject argument =
        Json.createObjectBuilder().add("asset_id", ASSET_ID).add("asc_order", true).build();

    // Act
    JsonObject result = scan.invoke(ledger, argument, Optional.empty());
    JsonArray data = result.getJsonArray("data");

    // Assert
    assertThat(data.size()).isEqualTo(3);
    assertThat(data.getJsonObject(0).getString("asset_id")).isEqualTo(ASSET_ID);
    assertThat(data.getJsonObject(0).getInt("age")).isEqualTo(0);
    assertThat(data.getJsonObject(1).getInt("age")).isEqualTo(1);
    assertThat(data.getJsonObject(2).getInt("age")).isEqualTo(2);
  }

  @Test
  public void invoke_GivenAscOrderingWithLimit_ShouldResultCorrectData() {
    // Arrange
    JsonObject argument =
        Json.createObjectBuilder()
            .add("asset_id", ASSET_ID)
            .add("limit", 1)
            .add("asc_order", true)
            .build();

    // Act
    JsonObject result = scan.invoke(ledger, argument, Optional.empty());
    JsonArray data = result.getJsonArray("data");

    // Assert
    assertThat(data.size()).isEqualTo(1);
    assertThat(data.getJsonObject(0).getString("asset_id")).isEqualTo(ASSET_ID);
    assertThat(data.getJsonObject(0).getInt("age")).isEqualTo(0);
  }

  @Test
  public void invoke_GivenStartVersionAscOrderingAndLimit_ShouldResultCorrectData() {
    // Arrange
    JsonObject argument =
        Json.createObjectBuilder()
            .add("asset_id", ASSET_ID)
            .add("start", 1)
            .add("limit", 1)
            .add("asc_order", true)
            .build();

    // Act
    JsonObject result = scan.invoke(ledger, argument, Optional.empty());
    JsonArray data = result.getJsonArray("data");

    // Assert
    assertThat(data.size()).isEqualTo(1);
    assertThat(data.getJsonObject(0).getString("asset_id")).isEqualTo(ASSET_ID);
    assertThat(data.getJsonObject(0).getInt("age")).isEqualTo(1);
  }
}
