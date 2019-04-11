package com.scalar.client.tool.explorer.command;

import com.scalar.client.tool.explorer.command.Explorer.ExplorerExecutor;
import javax.json.Json;
import javax.json.JsonObject;
import picocli.CommandLine;
import picocli.CommandLine.ParentCommand;

@CommandLine.Command(name = "get", description = "Get the current value of the specified asset")
public class Get implements Runnable {
  @CommandLine.Parameters(
      index = "0",
      paramLabel = "asset_id",
      description = "The id of the asset to get")
  private String assetId;

  @CommandLine.Option(
      names = {"--format"},
      description = "Select the output format: json or yaml%n(default: json)",
      defaultValue = "json")
  private String format;

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
      names = {"-v", "--verbose"},
      description = "Show verbose output")
  private boolean verbose;

  @ParentCommand private Explorer parent;

  @Override
  public void run() {
    ExplorerExecutor executor =
        explorer -> {
          try {
            JsonObject asset = explorer.get(assetId);
            parent.output(trim(asset), format);
          } catch (Exception e) {
            System.err.println(e.getMessage());
          }
        };
    parent.execute(file, executor);
  }

  private JsonObject trim(JsonObject object) {
    if (verbose) {
      return object;
    }
    return Json.createObjectBuilder()
        .add("id", object.getString("id"))
        .add("age", object.getJsonNumber("age").longValue())
        .add("data", object.getJsonObject("data"))
        .build();
  }
}
