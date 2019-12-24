package com.scalar.client.tool.emulator.function;

import com.scalar.database.api.Put;
import com.scalar.database.io.IntValue;
import com.scalar.database.io.Key;
import com.scalar.database.io.TextValue;
import com.scalar.ledger.database.MutableDatabase;
import com.scalar.ledger.udf.Function;
import java.util.Optional;
import javax.json.JsonObject;

public class StateUpdater extends Function {
  @Override
  public void invoke(
      MutableDatabase database, JsonObject contractArgument, Optional<JsonObject> funtionArgument) {

    funtionArgument.ifPresent(
        argument -> {
          String assetId = argument.getString("asset_id");
          int state = argument.getInt("state");
          if (assetId == "") {
            return;
          }

          Key key = new Key(new TextValue("asset_id", assetId));
          IntValue value = new IntValue("state", state);
          Put put = new Put(key).withValue(value);
          database.put(put);
        });
  }
}
