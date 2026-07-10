package com.scalar.dl.tools.e2e.populate;

import com.fasterxml.jackson.databind.JsonNode;
import com.scalar.dl.ledger.contract.JacksonBasedContract;
import com.scalar.dl.ledger.statemachine.Ledger;
import javax.annotation.Nullable;

/**
 * Minimal read-only contract that reads a single asset. Executed in auditor mode, the Auditor takes
 * a READ lock on the asset during ordering (reads bind no request_proof). If the Ledger is
 * unreachable at execution time, that READ lock is left held (stranded) — the read counterpart of
 * the stranded WRITE lock, exercising the READ branch of the Auditor lock recovery that
 * auditor-finalize-records drives.
 *
 * <p>Argument shape: {@code {"asset_id": "<string>"}}.
 */
public class GetAssetContract extends JacksonBasedContract {

  @Nullable
  @Override
  public JsonNode invoke(
      Ledger<JsonNode> ledger, JsonNode argument, @Nullable JsonNode properties) {
    ledger.get(argument.get("asset_id").asText());
    return null;
  }
}
