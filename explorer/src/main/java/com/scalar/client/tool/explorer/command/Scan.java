/*
 * This file is part of the Scalar DL Explorer.
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
 * a commercial license.  For more information, please contact Scalar, Inc.
 */

package com.scalar.client.tool.explorer.command;

import com.scalar.client.tool.explorer.command.Explorer.ExplorerExecutor;
import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonValue;
import picocli.CommandLine;
import picocli.CommandLine.ParentCommand;

@CommandLine.Command(name = "scan", description = "Get the history of the specified asset")
public class Scan implements Runnable {
  @CommandLine.Parameters(
      index = "0",
      paramLabel = "assetId",
      description = "The id of the asset to scan")
  private String assetId;

  @CommandLine.Option(
      names = {"--format"},
      description = "Select the output format: json or yaml%n(default: json)",
      defaultValue = "json")
  private String format;

  @CommandLine.Option(
      names = {"-a", "--ascending"},
      description = "Return assets in ascending order%n(default: descending)")
  private boolean ascendingOrder;

  @CommandLine.Option(
      names = {"-e", "--end"},
      description = "Return only assets with age < end")
  private int end;

  @CommandLine.Option(
      names = {"-f", "--file"},
      description =
          "Specify an alternative client.properties file%n(default: conf/client.properties)",
      defaultValue = "conf/client.properties")
  private String file;

  @CommandLine.Option(
      names = {"-h", "--help"},
      usageHelp = true,
      description = "Display this help and exit")
  private boolean help;

  @CommandLine.Option(
      names = {"-l", "--limit"},
      description = "The maximum number of assets to return%n(default: no limit)")
  private int limit;

  @CommandLine.Option(
      names = {"-s", "--start"},
      description = "Return only assets with age >= start")
  private int start;

  @CommandLine.Option(
      names = {"-v", "--verbose"},
      description = "Show verbose output")
  private boolean verbose;

  @ParentCommand private com.scalar.client.tool.explorer.command.Explorer parent;

  @Override
  public void run() {
    JsonObjectBuilder conditionBuilder = Json.createObjectBuilder();

    if (start >= 0) {
      conditionBuilder.add("start", start);
    }
    if (end > 0) {
      conditionBuilder.add("end", end);
    }
    if (limit > 0) {
      conditionBuilder.add("limit", limit);
    }
    if (ascendingOrder) {
      conditionBuilder.add("ascending", true);
    }

    ExplorerExecutor executor =
        explorer -> {
          try {
            JsonArray history = explorer.scan(assetId, conditionBuilder.build());
            parent.output(trim(history), format);
          } catch (Exception e) {
            System.err.println(e.getMessage());
          }
        };
    parent.execute(file, executor);
  }

  private JsonArray trim(JsonArray array) {
    if (verbose) {
      return array;
    }

    JsonArrayBuilder builder = Json.createArrayBuilder();
    for (JsonValue value : array) {
      JsonObject object = value.asJsonObject();
      builder.add(
          Json.createObjectBuilder()
              .add("id", object.getString("id"))
              .add("age", object.getJsonNumber("age").longValue())
              .add("data", object.getJsonObject("data"))
              .build());
    }
    return builder.build();
  }
}
