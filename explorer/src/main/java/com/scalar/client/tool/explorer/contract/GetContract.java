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

import com.scalar.dl.ledger.asset.Asset;
import com.scalar.dl.ledger.asset.InternalAsset;
import com.scalar.dl.ledger.contract.Contract;
import com.scalar.dl.ledger.exception.ContractContextException;
import com.scalar.dl.ledger.database.Ledger;
import java.util.Base64;
import java.util.Optional;
import javax.json.Json;
import javax.json.JsonObject;

public class GetContract extends Contract {
  @Override
  public JsonObject invoke(Ledger ledger, JsonObject argument, Optional<JsonObject> property) {
    if (!argument.containsKey("asset_id")) {
      throw new ContractContextException("asset_id attribute is missing");
    }

    String assetId = argument.getString("asset_id");
    Optional<Asset> asset = ledger.get(assetId);
    if (!asset.isPresent()) {
      throw new ContractContextException(assetId + " does not exist");
    }
    InternalAsset internal = (InternalAsset) asset.get();

    Base64.Encoder encoder = Base64.getEncoder();
    return Json.createObjectBuilder()
        .add("id", internal.id())
        .add("age", internal.age())
        .add("data", internal.data())
        .add("input", internal.input())
        .add("contract_id", internal.contractId())
        .add("argument", internal.argument())
        .add("signature", encoder.encodeToString(internal.signature()))
        .add(
            "prev_hash",
            (internal.prevHash() != null) ? encoder.encodeToString(internal.prevHash()) : "")
        .add("hash", encoder.encodeToString(internal.hash()))
        .build();
  }
}
