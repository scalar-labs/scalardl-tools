package com.scalar.client.tool.explorer.command;

import com.scalar.client.tool.explorer.command.Explorer.ExplorerExecutor;
import picocli.CommandLine;
import picocli.CommandLine.ParentCommand;

@CommandLine.Command(name = "contracts", description = "To list all registered contracts")
public class Contracts implements Runnable {
  @CommandLine.Option(
      names = {"-h", "--help"},
      usageHelp = true,
      description = "Display this help and exit")
  boolean help;

  @CommandLine.Option(
      names = {"-f", "--file"},
      description = "The path to scalar's 'client.properties' file",
      defaultValue = "conf/client.properties")
  private String file;

  @CommandLine.Option(
      names = {"-fm", "--format"},
      description = "Select the output format: json (default) or yaml",
      defaultValue = "json")
  private String outputFormat;

  @ParentCommand private Explorer parent;

  public void run() {
    ExplorerExecutor executor =
        explorer -> {
          try {
            parent.output(explorer.contracts(), outputFormat);
          } catch (Exception e) {
            System.err.println(e.getMessage());
          }
        };
    parent.execute(file, executor);
  }
}
