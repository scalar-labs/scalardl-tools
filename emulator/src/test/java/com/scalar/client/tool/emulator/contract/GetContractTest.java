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
import javax.json.JsonObject;
import org.junit.Test;

public class GetContractTest {
  private static final String ASSET_ID = "X";
  private PutContract put = new PutContract();
  private GetContract get = new GetContract();
  private TamperEvidentAssetbase assetbase = new AssetbaseEmulator();
  private Ledger ledger = new AssetLedger(assetbase);

  private void addAssetToLedger() {
    JsonObject argument =
        Json.createObjectBuilder()
            .add("asset_id", ASSET_ID)
            .add("data", Json.createObjectBuilder().build())
            .build();
    put.invoke(ledger, argument, Optional.empty());
  }

  @Test
  public void invoke_AssetIdGiven_ShouldResultInSuccess() {
    // Arrange
    addAssetToLedger();
    JsonObject argument = Json.createObjectBuilder().add("asset_id", ASSET_ID).build();

    // Act
    JsonObject result = get.invoke(ledger, argument, Optional.empty());

    // Assert
    assertThat(result.getString("asset_id")).isEqualTo(ASSET_ID);
  }

  @Test
  public void invoke_AssetNotInLedger_ShouldResultInFailure() {
    // Arrange
    JsonObject argument = Json.createObjectBuilder().add("asset_id", ASSET_ID).build();

    // Act
    JsonObject result = get.invoke(ledger, argument, Optional.empty());

    // Assert
    assertThat(result.getString("result")).isEqualTo("failure");
    assertThat(result.getString("message")).endsWith("is not in the ledger");
  }

  @Test
  public void invoke_AssetIdNotGiven_ShouldThrowContractContextException() {
    // Arrange
    JsonObject argument = Json.createObjectBuilder().build();

    // Act-assert
    assertThatThrownBy(() -> get.invoke(ledger, argument, Optional.empty()))
        .isInstanceOf(ContractContextException.class);
  }
}
