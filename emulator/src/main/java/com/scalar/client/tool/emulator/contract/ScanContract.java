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

import com.scalar.ledger.contract.Contract;
import com.scalar.ledger.database.AssetFilter;
import com.scalar.ledger.database.AssetFilter.VersionOrder;
import com.scalar.ledger.exception.ContractContextException;
import com.scalar.ledger.ledger.Ledger;
import java.util.Optional;
import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;

public class ScanContract extends Contract {

  private void checkNonNegative(int value) {
    if (value < 0) {
      throw new ContractContextException("value must be non-negative");
    }
  }

  @Override
  public JsonObject invoke(Ledger ledger, JsonObject argument, Optional<JsonObject> property) {
    if (!argument.containsKey("asset_id")) {
      throw new ContractContextException("asset_id attribute is missing");
    }

    AssetFilter filter = new AssetFilter(argument.getString("asset_id"));

    if (argument.containsKey("start")) {
      int start = argument.getInt("start");
      checkNonNegative(start);
      filter.withStartVersion(start, true);
    }

    if (argument.containsKey("end")) {
      int end = argument.getInt("end");
      checkNonNegative(end);
      filter.withEndVersion(end, false);
    }

    if (argument.containsKey("limit")) {
      int limit = argument.getInt("limit");
      checkNonNegative(limit);
      filter.withLimit(limit);
    }

    if (argument.containsKey("asc_order")) {
      boolean ascendingOrder = argument.getBoolean("asc_order");
      if (ascendingOrder) {
        filter.withVersionOrder(VersionOrder.ASC);
      } else {
        filter.withVersionOrder(VersionOrder.DESC);
      }
    }

    JsonArrayBuilder assets = Json.createArrayBuilder();
    ledger
        .scan(filter)
        .forEach(
            asset -> {
              JsonObject json =
                  Json.createObjectBuilder()
                      .add("asset_id", asset.id())
                      .add("age", asset.age())
                      .add("data", asset.data())
                      .build();
              assets.add(json);
            });

    return Json.createObjectBuilder().add("data", assets.build()).build();
  }
}
