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
package com.scalar.client.tool.emulator.command;

import static com.google.common.base.Preconditions.checkArgument;

import com.scalar.client.tool.emulator.ContractManagerEmulator;
import com.scalar.client.tool.emulator.TerminalWrapper;
import com.scalar.database.api.Delete;
import com.scalar.database.api.Get;
import com.scalar.database.api.Put;
import com.scalar.database.api.Result;
import com.scalar.database.api.Scan;
import com.scalar.database.io.BooleanValue;
import com.scalar.database.io.DoubleValue;
import com.scalar.database.io.IntValue;
import com.scalar.database.io.Key;
import com.scalar.database.io.TextValue;
import com.scalar.database.io.Value;
import com.scalar.ledger.database.TamperEvidentAssetbase;
import com.scalar.ledger.emulator.MutableDatabaseEmulator;
import com.scalar.ledger.ledger.Ledger;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.inject.Inject;
import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonNumber;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonReader;
import javax.json.JsonString;
import javax.json.JsonValue;
import picocli.CommandLine;

@CommandLine.Command(
    name = "database",
    sortOptions = false,
    usageHelpWidth = TerminalWrapper.USAGE_HELP_WIDTH,
    headerHeading = "%n@|bold,underline Usage|@:%n",
    synopsisHeading = "",
    descriptionHeading = "%n@|bold,underline Description|@:%n",
    description = "<TBD>",
    parameterListHeading = "%n@|bold,underline Parameters|@:%n",
    optionListHeading = "%n@|bold,underline Options|@:%n",
    footerHeading = "%n",
    footer = "Usage example: 'database'.%n")
public class Database extends AbstractCommand implements Runnable {
  @CommandLine.Parameters(
      index = "0",
      paramLabel = "method",
      description = "method that will be execute by MutableDatabase, e.g 'get'")
  private String method;

  @CommandLine.Option(
      names = {"-p", "--primarykey"},
      paramLabel = "key",
      description = "the primary key (in JSON) that is to be inserted into the MutableDatabase")
  private String primaryKey;

  @CommandLine.Option(
      names = {"-c", "--clusteringkey"},
      paramLabel = "key",
      description = "the primary key (in JSON) that is to be inserted into the MutableDatabase")
  private String clusteringKey;

  @CommandLine.Option(
      names = {"-v", "--value"},
      paramLabel = "value",
      description = "the value (in JSON) that is to be inserted into the MutableDatabase")
  private String value;

  @CommandLine.Option(
      names = {"-n", "--namespace"},
      paramLabel = "namespace",
      description = "the namespace of the database")
  private String namespace;

  @CommandLine.Option(
      names = {"-t", "--table"},
      paramLabel = "table",
      description = "the table of the database")
  private String table;

  private MutableDatabaseEmulator databaseEmulator;

  @Inject
  public Database(
      MutableDatabaseEmulator databaseEmulator,
      TerminalWrapper terminal,
      ContractManagerEmulator contractManager,
      TamperEvidentAssetbase assetbase,
      Ledger ledger) {
    super(terminal, contractManager, assetbase, ledger);
    this.databaseEmulator = databaseEmulator;
  }

  @Override
  public void run() {
    if (method.equals("get")) {
      runGet();
    } else if (method.equals("delete")) {
      runDelete();
    } else if (method.equals("put")) {
      runPut();
    } else if (method.equals("scan")) {
      runScan();
    }
  }

  private void runGet() {
    checkArgument(primaryKey != null, "primary key cannot be null");
    Get get =
        (clusteringKey != null)
            ? new Get(new Key(values(primaryKey)), new Key(values(clusteringKey)))
            : new Get(new Key(values(primaryKey)));
    if (namespace != null) {
      get.forNamespace(namespace);
    }
    if (table != null) {
      get.forTable(table);
    }
    System.out.println(json(databaseEmulator.get(get)));
  }

  private void runDelete() {
    checkArgument(primaryKey != null, "primary key cannot be null");
    Delete delete =
        (clusteringKey != null)
            ? new Delete(new Key(values(primaryKey)), new Key(values(clusteringKey)))
            : new Delete(new Key(values(clusteringKey)));
    if (namespace != null) {
      delete.forNamespace(namespace);
    }
    if (table != null) {
      delete.forTable(table);
    }
    databaseEmulator.delete(delete);
  }

  private void runPut() {
    checkArgument(primaryKey != null, "primary key cannot be null");
    checkArgument(value != null, "value cannot be null");
    Put put =
        (clusteringKey != null)
            ? new Put(new Key(values(primaryKey)), new Key(values(clusteringKey)))
            : new Put(new Key(values(primaryKey)));
    if (namespace != null) {
      put.forNamespace(namespace);
    }
    if (table != null) {
      put.forTable(table);
    }
    put.withValues(values(value));
    databaseEmulator.put(put);
  }

  private void runScan() {
    checkArgument(primaryKey != null, "primary key cannot be null");
    Scan scan = new Scan(new Key(values(primaryKey)));
    if (namespace != null) {
      scan.forNamespace(namespace);
    }
    if (table != null) {
      scan.forTable(table);
    }
    // TODO support filter?
    System.out.println(json(databaseEmulator.scan(scan)));
  }

  private List<Value> values(String json) {
    List<Value> values = new ArrayList<Value>();
    JsonReader reader = Json.createReader(new StringReader(json));
    JsonObject object = reader.readObject();

    Iterator<Map.Entry<String, JsonValue>> iterator = object.entrySet().iterator();
    while (iterator.hasNext()) {
      Map.Entry<String, JsonValue> entry = iterator.next();
      String key = entry.getKey();
      JsonValue value = entry.getValue();
      switch (value.getValueType().name()) {
        case "STRING":
          values.add(new TextValue(key, ((JsonString) value).getString()));
          break;
        case "TRUE":
          values.add(new BooleanValue(key, true));
          break;
        case "FALSE":
          values.add(new BooleanValue(key, false));
          break;
        case "NUMBER":
          JsonNumber n = (JsonNumber) value;
          if (n.isIntegral()) {
            values.add(new IntValue(key, n.intValue()));
          } else {
            values.add(new DoubleValue(key, n.doubleValue()));
          }
          break;
        default:
          // TODO handle other types?
      }
    }

    return values;
  }

  private JsonObject json(Result result) {
    JsonObjectBuilder builder = Json.createObjectBuilder();
    Iterator<Map.Entry<String, Value>> iterator = result.getValues().entrySet().iterator();
    while (iterator.hasNext()) {
      Map.Entry<String, Value> entry = iterator.next();
      String key = entry.getKey();
      Value value = entry.getValue();
      switch (value.getClass().getSimpleName()) {
        case "TextValue":
          builder.add(key, ((TextValue) value).getString().get());
          break;
        case "IntValue":
          builder.add(key, ((IntValue) value).get());
          break;
        case "DoubleValue":
          builder.add(key, ((DoubleValue) value).get());
          break;
        case "BooleanValue":
          builder.add(key, ((BooleanValue) value).get());
          break;
        default:
          // TODO handle other types?
      }
    }

    return builder.build();
  }

  private String json(Optional<Result> result) {
    return result.isPresent() ? json(result.get()).toString() : "{}";
  }

  private String json(List<Result> results) {
    JsonArrayBuilder array = Json.createArrayBuilder();
    for (Result result : results) {
      array.add(json(result));
    }

    return array.build().toString();
  }
}
