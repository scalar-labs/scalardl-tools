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

@CommandLine.Command(name = "scan", description = "To display the history of the specified asset")
public class Scan implements Runnable {
  @CommandLine.Option(
      names = {"-h", "--help"},
      usageHelp = true,
      description = "Display this help and exit")
  boolean help;

  @CommandLine.Parameters(
      index = "0",
      paramLabel = "assetId",
      description = "the id of the asset to scan")
  private String assetId;

  @CommandLine.Option(
      names = {"-s", "--start"},
      description = "return only assets with age >= start")
  private int start;

  @CommandLine.Option(
      names = {"-e", "--end"},
      description = "return only assets with age < end")
  private int end;

  @CommandLine.Option(
      names = {"-a", "--ascending"},
      description =
          "add this flag to return assets in ascending order. The default order is descending")
  private boolean ascendingOrder;

  @CommandLine.Option(
      names = {"-l", "--limit"},
      description =
          "an integer > 0 which is the maximum number of assets returned. By default there is no limit")
  private int limit;

  @CommandLine.Option(
      names = {"-fm", "--format"},
      description = "Select the output format: json (default) or yaml",
      defaultValue = "json")
  private String outputFormat;

  @CommandLine.Option(
      names = {"-f", "--file"},
      description = "The path to scalar's 'client.properties' file",
      defaultValue = "client.properties")
  private String file;

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
            parent.output(trim(history), outputFormat);
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
