package com.scalar.client.tool.emulator.function;

import com.scalar.db.api.Put;
import com.scalar.db.io.IntValue;
import com.scalar.db.io.Key;
import com.scalar.db.io.TextValue;
import com.scalar.dl.ledger.database.Database;
import com.scalar.dl.ledger.function.Function;
import java.util.Optional;
import javax.json.JsonObject;

public class StateUpdater extends Function {
  @Override
  public void invoke(
      Database database,
      Optional<JsonObject> funtionArgument,
      JsonObject contractArgument,
      Optional<JsonObject> contractProperties) {

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
