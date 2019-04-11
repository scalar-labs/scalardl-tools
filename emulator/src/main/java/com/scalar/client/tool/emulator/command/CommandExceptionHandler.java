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

import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.jline.utils.WriterOutputStream;
import picocli.CommandLine;

public class CommandExceptionHandler extends CommandLine.DefaultExceptionHandler<List<Object>> {
  public CommandExceptionHandler(PrintWriter output) {
    OutputStream os = new WriterOutputStream(output, StandardCharsets.UTF_8);
    PrintStream ps = new PrintStream(os);
    useErr(ps);
  }

  @Override
  public List<Object> handleExecutionException(
      CommandLine.ExecutionException ex, CommandLine.ParseResult parseResult) {
    ex.getCause().printStackTrace(err());
    return null;
  }
}
