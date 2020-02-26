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

import static com.google.common.base.Preconditions.checkArgument;

import com.scalar.client.tool.emulator.TerminalWrapper;
import com.scalar.dl.ledger.exception.RegistryIOException;
import com.scalar.dl.ledger.function.FunctionEntry;
import com.scalar.dl.ledger.function.FunctionManager;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import javax.inject.Inject;
import picocli.CommandLine;

@CommandLine.Command(
    name = "register-function",
    sortOptions = false,
    usageHelpWidth = TerminalWrapper.USAGE_HELP_WIDTH,
    headerHeading = "%n@|bold,underline Usage|@:%n",
    synopsisHeading = "",
    descriptionHeading = "%n@|bold,underline Description|@:%n",
    description = "Use this command to register user-defined functions",
    parameterListHeading = "%n@|bold,underline Parameters|@:%n",
    optionListHeading = "%n@|bold,underline Options|@:%n",
    footerHeading = "%n",
    footer = "Usage example: 'register-function'.%n")
public class RegisterFunction implements Runnable {
  @CommandLine.Parameters(
      index = "0",
      paramLabel = "id",
      description = "id that will be used when executing the function")
  private String id;

  @CommandLine.Parameters(index = "1", paramLabel = "name", description = "function canonical name")
  private String name;

  @CommandLine.Parameters(index = "2", paramLabel = "file", description = "compiled function class file")
  private File functionFile;

  private FunctionManager functionManager;

  private TerminalWrapper terminal;

  @Inject
  public RegisterFunction(TerminalWrapper terminal, FunctionManager functionManager) {
    this.functionManager = functionManager;
    this.terminal = terminal;
  }

  @Override
  public void run() {
    checkArgument(id != null, "id cannot be null");
    checkArgument(name != null, "name cannot be null");
    checkArgument(functionFile != null, "function file cannot be null");

    try {
      byte[] bytes = Files.readAllBytes(functionFile.toPath());
      long registeredAt = System.currentTimeMillis();
      functionManager.register(new FunctionEntry(id, name, bytes, registeredAt));
      terminal.println("Function '" + id + "' successfully registered");
    } catch (IOException e) {
      throw new RegistryIOException("could not register function " + id);
    }
  }
}
