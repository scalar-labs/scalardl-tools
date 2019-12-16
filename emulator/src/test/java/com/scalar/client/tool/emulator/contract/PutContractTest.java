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

import com.scalar.ledger.database.TamperEvidentAssetbase;
import com.scalar.ledger.emulator.AssetbaseEmulator;
import com.scalar.ledger.exception.ContractContextException;
import com.scalar.ledger.ledger.AssetLedger;
import com.scalar.ledger.ledger.Ledger;
import java.util.Optional;
import javax.json.Json;
import javax.json.JsonObject;
import org.junit.Test;

public class PutContractTest {
  private static final String ASSET_ID = "X";
  private PutContract contract = new PutContract();
  private TamperEvidentAssetbase assetbase = new AssetbaseEmulator();
  private Ledger ledger = new AssetLedger(assetbase);

  @Test
  public void invoke_AssetIdAndDataGiven_ShouldResultInSuccess() {
    // Arrange
    JsonObject argument =
        Json.createObjectBuilder()
            .add("asset_id", ASSET_ID)
            .add("data", Json.createObjectBuilder().build())
            .build();

    // Act
    JsonObject result = contract.invoke(ledger, argument, Optional.empty());

    // Assert
    assertThat(result).isEqualTo(null);
  }

  @Test
  public void invoke_AssetIdNotGiven_ShouldThrowContractContextException() {
    // Arrange
    JsonObject argument = Json.createObjectBuilder().build();

    // Act-assert
    assertThatThrownBy(() -> contract.invoke(ledger, argument, Optional.empty()))
        .isInstanceOf(ContractContextException.class);
  }
}
