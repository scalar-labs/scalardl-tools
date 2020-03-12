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
package com.scalar.client.tool.emulator;

import com.google.common.annotations.VisibleForTesting;
import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.scalar.client.tool.emulator.command.CommandExceptionHandler;
import com.scalar.client.tool.emulator.command.Database;
import com.scalar.client.tool.emulator.command.Execute;
import com.scalar.client.tool.emulator.command.Get;
import com.scalar.client.tool.emulator.command.GetWithSingleParameter;
import com.scalar.client.tool.emulator.command.ListContracts;
import com.scalar.client.tool.emulator.command.Put;
import com.scalar.client.tool.emulator.command.PutWithSingleParameter;
import com.scalar.client.tool.emulator.command.Register;
import com.scalar.client.tool.emulator.command.RegisterFunction;
import com.scalar.client.tool.emulator.command.Scan;
import com.scalar.client.tool.emulator.command.ScanWithSingleParameter;
import com.scalar.client.tool.emulator.command.SetCertificate;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Stack;
import java.util.stream.Stream;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;
import picocli.CommandLine;

@CommandLine.Command(
    name = "emulator",
    sortOptions = false,
    usageHelpWidth = TerminalWrapper.USAGE_HELP_WIDTH,
    headerHeading = "%n@|bold,underline Usage|@:%n",
    descriptionHeading = "%n@|bold,underline Description|@:%n",
    description = "Interactive emulator for Scalar DL",
    parameterListHeading = "%n@|bold,underline Parameters|@:%n",
    optionListHeading = "%n@|bold,underline Options|@:%n")
public class EmulatorTerminal implements Runnable {
  private static List<CommandLine> commands;
  private TerminalWrapper terminal;
  private ContractManagerEmulator contractManager;
  private boolean shouldExit;

  @CommandLine.Option(
      names = {"-f", "--file"},
      paramLabel = "FILE",
      description =
          "a file containing a list of commands that will be executed. The file should contain one command at most per line")
  private File commandsFile;

  @CommandLine.Option(
      names = {"-h", "--help"},
      description = "print the help and exit",
      usageHelp = true)
  private boolean help;

  @Inject
  public EmulatorTerminal(TerminalWrapper terminal, ContractManagerEmulator contractManager) {
    this.terminal = terminal;
    this.contractManager = contractManager;

    preregisterContracts();
  }

  public static void main(String[] args) {
    Injector injector = Guice.createInjector(new EmulatorModule());

    commands =
        Arrays.asList(
            new CommandLine(injector.getInstance(Execute.class)),
            new CommandLine(injector.getInstance(GetWithSingleParameter.class)),
            new CommandLine(injector.getInstance(Get.class)),
            new CommandLine(injector.getInstance(ListContracts.class)),
            new CommandLine(injector.getInstance(PutWithSingleParameter.class)),
            new CommandLine(injector.getInstance(Put.class)),
            new CommandLine(injector.getInstance(RegisterFunction.class)),
            new CommandLine(injector.getInstance(Register.class)),
            new CommandLine(injector.getInstance(ScanWithSingleParameter.class)),
            new CommandLine(injector.getInstance(Scan.class)),
            new CommandLine(injector.getInstance(Database.class)),
            new CommandLine(injector.getInstance(SetCertificate.class)));

    CommandLine.run(injector.getInstance(EmulatorTerminal.class), args);
  }

  @Override
  public void run() {
    LineReader inputReader = terminal.setUpAutoCompletionAndTerminalHistory(commands);

    if (commandsFile != null) {
      executeCommandsFile();
    } else {
      terminal.printWelcomeMessage();
    }

    terminal.resume();

    while (!shouldExit) {
      try {
        String line = inputReader.readLine(TerminalWrapper.LINE_HEADER);
        if (!processLine(line)) {
          terminal.println("Unknown command: " + line);
        }
      } catch (UserInterruptException e) {
        break;
      } catch (EndOfFileException e) {
        terminal.println(e.getMessage());
        break;
      }
    }
  }

  private void preregisterContracts() {
    Path parent =
        Paths.get(
            "build",
            "classes",
            "java",
            "main",
            "com",
            "scalar",
            "client",
            "tool",
            "emulator",
            "contract");
    contractManager.register(
        "get",
        "com.scalar.client.tool.emulator.contract.GetContract",
        new File(parent.toFile(), "GetContract.class"),
        null);
    contractManager.register(
        "put",
        "com.scalar.client.tool.emulator.contract.PutContract",
        new File(parent.toFile(), "PutContract.class"),
        null);
    contractManager.register(
        "scan",
        "com.scalar.client.tool.emulator.contract.ScanContract",
        new File(parent.toFile(), "ScanContract.class"),
        null);
  }

  private void executeCommandsFile() {
    try (Stream<String> stream = Files.lines(commandsFile.toPath())) {
      stream.forEach(
          line -> {
            terminal.println(line, true);
            processLine(line);
          });
    } catch (IOException e) {
      terminal.println(e.getMessage());
    }
  }

  private boolean processLine(String line) {
    line = line.trim();
    if (line.equals("help")) {
      printHelp();
      return true;
    } else if (line.equals("exit") || line.equals("quit")) {
      shouldExit = true;
      return true;
    } else {
      return parseAndRunCommand(line);
    }
  }

  private void printHelp() {
    terminal.println("Available commands:");
    commands.stream()
        .map(CommandLine::getCommandName)
        .sorted(String::compareToIgnoreCase)
        .forEach(commandName -> terminal.println(" - " + commandName));
    terminal.println(" - help");
    terminal.println(" - exit");
    terminal.println("Type '<command> -h' to display help for the command.");
  }

  private boolean parseAndRunCommand(String line) {
    for (CommandLine command : commands) {
      if (line.startsWith(command.getCommandName())) {
        String params = line.replaceFirst(command.getCommandName(), "").trim();
        String[] paramsArray = params.isEmpty() ? new String[] {} : paramsParser(params);
        command.parseWithHandlers(
            new CommandLine.RunFirst(),
            new CommandExceptionHandler(terminal.getTerminal().writer()),
            paramsArray);
        return true;
      }
    }
    return false;
  }

  @VisibleForTesting
  String[] paramsParser(String params) {
    Stack<String> stack = new Stack<>();
    List<String> paramsList = new ArrayList<String>();
    ByteArrayOutputStream byteArray = new ByteArrayOutputStream();

    for (int i = 0; i < params.length(); i++) {
      String character = Character.valueOf(params.charAt(i)).toString();
      if (character.matches("[{\"}]")) {
        if (!stack.empty()) {
          if ((character.equals("\"") && stack.peek().equals("\""))
              || (character.equals("}") && stack.peek().equals("{"))) {
            stack.pop();
          } else {
            stack.push(character);
          }
        } else {
          stack.push(character);
        }
      }

      byteArray.write(params.charAt(i));
      if ((character.matches(" ") && stack.empty()) || i == params.length() - 1) {
        paramsList.add(byteArray.toString().trim().replaceAll("^\"|\"$", ""));
        byteArray.reset();
      }
    }
    return paramsList.toArray(new String[0]);
  }
}
