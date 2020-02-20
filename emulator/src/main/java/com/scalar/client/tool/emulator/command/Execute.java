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

import com.scalar.client.tool.emulator.ContractManagerEmulator;
import com.scalar.client.tool.emulator.TerminalWrapper;
import com.scalar.dl.ledger.database.Ledger;
import com.scalar.dl.ledger.database.TamperEvidentAssetbase;
import com.scalar.dl.ledger.emulator.MutableDatabaseEmulator;
import com.scalar.dl.ledger.function.Function;
import com.scalar.dl.ledger.function.FunctionManager;
import java.util.List;
import java.util.Optional;
import javax.inject.Inject;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonString;
import picocli.CommandLine;

@CommandLine.Command(
    name = "execute",
    sortOptions = false,
    usageHelpWidth = TerminalWrapper.USAGE_HELP_WIDTH,
    headerHeading = "%n@|bold,underline Usage|@:%n",
    synopsisHeading = "",
    descriptionHeading = "%n@|bold,underline Description|@:%n",
    description = "Execute a registered contract.",
    parameterListHeading = "%n@|bold,underline Parameters|@:%n",
    optionListHeading = "%n@|bold,underline Options|@:%n",
    footerHeading = "%n",
    footer = "For example: 'execute get {\"asset_id\":\"foo\"}'%n")
public class Execute extends AbstractCommand {
  @CommandLine.Parameters(
      index = "0",
      paramLabel = "id",
      description =
          "contract id. Use 'list-contracts' to list all the registered contracts and their ids.")
  private String id;

  @CommandLine.Parameters(
      index = "1..*",
      arity = "1..*",
      paramLabel = "argument",
      description =
          "the JSON contract argument. A plain text JSON object or the path to a file containing a JSON object")
  private List<String> argument;

  @CommandLine.Option(
      names = {"-fa", "--function_argument"},
      description = "the argument passed to UDF")
  private String functionArgument;

  private MutableDatabaseEmulator databaseEmulator;

  private FunctionManager functionManager;

  @Inject
  public Execute(
      TerminalWrapper terminal,
      ContractManagerEmulator contractManager,
      TamperEvidentAssetbase assetbase,
      Ledger ledger,
      FunctionManager functionManager,
      MutableDatabaseEmulator databaseEmulator) {
    super(terminal, contractManager, assetbase, ledger);
    this.databaseEmulator = databaseEmulator;
    this.functionManager = functionManager;
  }

  @Override
  public void run() {
    JsonObject json = convertJsonParameter(argument);
    if (json != null) {
      executeContract(toKey(id), json);
    }

    JsonArray functions = json.getJsonArray("_functions_");
    if (functions != null) {
      functions.forEach(
          func -> {
            String functionId = ((JsonString) func).getString();
            Function f = functionManager.getInstance(functionId);
            f.invoke(
                databaseEmulator,
                Optional.ofNullable(convertJsonParameter(functionArgument)),
                json,
                contractManager.get(toKey(id)).getProperties());
          });
    }
  }
}
