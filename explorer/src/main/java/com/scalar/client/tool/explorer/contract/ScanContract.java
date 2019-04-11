package com.scalar.client.tool.explorer.contract;

import com.scalar.ledger.asset.Asset;
import com.scalar.ledger.asset.InternalAsset;
import com.scalar.ledger.contract.Contract;
import com.scalar.ledger.database.AssetFilter;
import com.scalar.ledger.exception.ContractContextException;
import com.scalar.ledger.ledger.Ledger;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;

public class ScanContract extends Contract {
  @Override
  public JsonObject invoke(Ledger ledger, JsonObject argument, Optional<JsonObject> property) {
    if (!argument.containsKey("asset_id")) {
      throw new ContractContextException("asset_id attribute is missing");
    }

    AssetFilter filter = new AssetFilter(argument.getString("asset_id"));

    if (argument.containsKey("start")) {
      filter.withStartVersion(argument.getInt("start"), true);
    }

    if (argument.containsKey("end")) {
      filter.withEndVersion(argument.getInt("end"), false);
    }

    if (argument.containsKey("limit")) {
      filter.withLimit(argument.getInt("limit"));
    }

    if (argument.containsKey("ascending")) {
      filter.withVersionOrder(AssetFilter.VersionOrder.ASC);
    } else {
      filter.withVersionOrder(AssetFilter.VersionOrder.DESC);
    }

    List<Asset> history = ledger.scan(filter);

    JsonArrayBuilder arrayBuilder = Json.createArrayBuilder();
    Base64.Encoder encoder = Base64.getEncoder();
    for (Asset asset : history) {
      InternalAsset internal = (InternalAsset) asset;

      arrayBuilder.add(
          Json.createObjectBuilder()
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
              .build());
    }
    return Json.createObjectBuilder().add("history", arrayBuilder.build()).build();
  }
}
