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

import java.io.File;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import javax.annotation.concurrent.Immutable;
import org.jline.builtins.Completers;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.impl.completer.AggregateCompleter;
import org.jline.reader.impl.completer.StringsCompleter;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.terminal.Terminal;
import picocli.CommandLine;

@Immutable
public class TerminalWrapper {
  private final Terminal terminal;
  public static final int USAGE_HELP_WIDTH = 150;
  public static final String LINE_HEADER = "scalar> ";

  public TerminalWrapper(Terminal terminal) {
    this.terminal = terminal;
  }

  public Terminal getTerminal() {
    return terminal;
  }

  public void println(String text, boolean withHeader) {
    if (withHeader) {
      text = LINE_HEADER + text;
    }
    this.terminal.writer().println(text);
    this.terminal.writer().flush();
  }

  public void println(String text) {
    this.println(text, false);
  }

  public void printWelcomeMessage() {
    println("Scalar DL Emulator");
    println("Type 'help' for more information");
  }

  public void resume() {
    terminal.resume();
  }

  public LineReader setUpAutoCompletionAndTerminalHistory(List<CommandLine> commands) {
    List<String> commandsName =
        commands.stream().map(e -> e.getCommandName()).collect(Collectors.toList());
    commandsName.add("help");
    commandsName.add("exit");

    AggregateCompleter completer =
        new AggregateCompleter(
            new StringsCompleter(commandsName),
            new Completers.FileNameCompleter(),
            new Completers.DirectoriesCompleter(new File(System.getProperty("user.dir"))));

    LineReader inputReader =
        LineReaderBuilder.builder()
            .history(new DefaultHistory())
            .completer(completer)
            .terminal(terminal)
            .variable(
                // Location of the file where the executed commands history is saved
                LineReader.HISTORY_FILE,
                Paths.get(System.getProperty("user.home"), ".scalardl_emulator_history"))
            .build();

    return inputReader;
  }
}
