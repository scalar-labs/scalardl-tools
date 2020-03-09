package com.scalar.client.tool.explorer.command;

import picocli.CommandLine;

@CommandLine.Command(name = "initialize", description = "Initialize the asset management application")
public class Initialize implements Runnable {
  @CommandLine.Option(
      names = {"-f", "--file"},
      description = "Specify an alternative client.properties file%n(default: client.properties)",
      defaultValue = "client.properties")
  private String file;

  @CommandLine.Option(
      names = {"-h"},
      usageHelp = true,
      description = "display this help and exit")
  private boolean help;

  @CommandLine.ParentCommand private Explorer parent;

  @Override
  public void run() {
    Explorer.ExplorerExecutor executor =
        explorer -> {
          try {
            explorer.initialize();
          } catch (Exception e) {
            System.err.println(e.getMessage());
          }
    };
    parent.execute(file, executor);
  }
}
