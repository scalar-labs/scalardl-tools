package com.scalar.client.tool.explorer.command;

import com.scalar.client.tool.explorer.command.Explorer.ExplorerExecutor;
import picocli.CommandLine;
import picocli.CommandLine.ParentCommand;

@CommandLine.Command(name = "validate", description = "Validate the specified asset")
public class Validate implements Runnable {
  @CommandLine.Parameters(
      paramLabel = "asset_id",
      description = "The id of the asset to validate",
      arity = "1..*")
  private String[] assetIds;

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

  @ParentCommand private Explorer parent;

  public void run() {
    ExplorerExecutor executor =
        explorer -> {
          try {
            for (String assetId : assetIds) {
              explorer.validate(assetId);
              System.out.println(assetId + " is not tampered");
            }
          } catch (Exception e) {
            System.err.println(e.getMessage());
          }
        };
    parent.execute(file, executor);
  }
}
