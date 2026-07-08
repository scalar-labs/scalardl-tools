package com.scalar.dl.tools.e2e.populate;

import com.fasterxml.jackson.databind.JsonNode;
import com.scalar.dl.ledger.contract.JacksonBasedContract;
import com.scalar.dl.ledger.statemachine.Ledger;
import javax.annotation.Nullable;

/**
 * Minimal contract that writes a single asset record. Executed in auditor mode it produces a
 * committed {@code scalar.asset}, an {@code auditor.request_proof}, a {@code coordinator.state}
 * record, and a released {@code auditor.asset_lock} — i.e. the "old data" the cleanup tools later
 * reclaim.
 *
 * <p>Argument shape: {@code {"asset_id": "<string>", "amount": <int>}}.
 */
public class PutAssetContract extends JacksonBasedContract {

  @Nullable
  @Override
  public JsonNode invoke(
      Ledger<JsonNode> ledger, JsonNode argument, @Nullable JsonNode properties) {
    String assetId = argument.get("asset_id").asText();
    JsonNode value =
        getObjectMapper().createObjectNode().put("balance", argument.get("amount").asInt());
    ledger.put(assetId, value);
    return null;
  }
}
