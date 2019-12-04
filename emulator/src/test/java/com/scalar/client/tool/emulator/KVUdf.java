package com.scalar.client.tool.emulator;

import com.scalar.database.api.Put;
import com.scalar.database.io.Key;
import com.scalar.database.io.TextValue;
import com.scalar.ledger.database.MutableDatabase;
import com.scalar.ledger.udf.Function;
import java.util.Optional;
import javax.json.JsonObject;

class KVUdf extends Function {
  @Override
  public void invoke(
      MutableDatabase database, JsonObject contractArgument, Optional<JsonObject> funtionArgument) {

    funtionArgument.ifPresent(
        argument -> {
          String k = argument.getString("key");
          String v = argument.getString("value", "");
          if (k == "") {
            return;
          }

          Key key = new Key(new TextValue("key", k));
          TextValue value = new TextValue("value", v);
          Put put = new Put(key).withValue(value);
          database.put(put);
        });
  }
}
