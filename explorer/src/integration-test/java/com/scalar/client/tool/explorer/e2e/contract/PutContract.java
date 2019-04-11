package com.scalar.client.tool.explorer.e2e.contract;

import com.scalar.ledger.contract.Contract;
import com.scalar.ledger.ledger.Ledger;
import java.util.Optional;
import javax.json.Json;
import javax.json.JsonObject;

public class PutContract extends Contract {
  public static final String NAME = "name";
  public static final String TYPE = "type";
  private static final String RESULT = "result";
  private static final String SUCCESS = "success";
  public static final String ASSET = "asset";
  public static final String ID = "id";
  public static final String TIMESTAMP = "timestamp";

  @Override
  public JsonObject invoke(Ledger ledger, JsonObject argument, Optional<JsonObject> property) {

    String type = argument.getString(TYPE);
    String id = argument.getString(ID);
    String name = argument.getString(ASSET);
    long timestamp = argument.getJsonNumber(TIMESTAMP).longValue();

    ledger.get(type);
    ledger.get(id);
    JsonObject assetStatusJson = Json.createObjectBuilder().add(TIMESTAMP, timestamp).build();
    ledger.put(id, assetStatusJson);
    JsonObject assetNameJson = Json.createObjectBuilder().add(ID, id).add(NAME, name).build();
    ledger.put(type, assetNameJson);

    return Json.createObjectBuilder().add(RESULT, SUCCESS).build();
  }
}
