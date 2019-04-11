package com.scalar.client.tool.explorer.command;

import com.scalar.client.tool.explorer.command.Explorer.ExplorerExecutor;
import javax.json.Json;
import javax.json.JsonObject;
import picocli.CommandLine;
import picocli.CommandLine.ParentCommand;

@CommandLine.Command(name = "get", description = "To get the current value of the specified asset")
public class Get implements Runnable {
  @CommandLine.Option(
      names = {"-h", "--help"},
      usageHelp = true,
      description = "Display this help and exit")
  boolean help;

  @CommandLine.Parameters(
      index = "0",
      paramLabel = "asset_id",
      description = "The id of the asset to get")
  private String assetId;

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

  @ParentCommand private Explorer parent;

  @Override
  public void run() {
    ExplorerExecutor executor =
        explorer -> {
          try {
            JsonObject asset = explorer.get(assetId);
            parent.output(trim(asset), outputFormat);
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
