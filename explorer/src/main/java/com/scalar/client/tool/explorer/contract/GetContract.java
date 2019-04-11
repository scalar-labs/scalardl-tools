package com.scalar.client.tool.explorer.contract;

import com.scalar.ledger.asset.Asset;
import com.scalar.ledger.asset.InternalAsset;
import com.scalar.ledger.contract.Contract;
import com.scalar.ledger.exception.ContractContextException;
import com.scalar.ledger.ledger.Ledger;
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
