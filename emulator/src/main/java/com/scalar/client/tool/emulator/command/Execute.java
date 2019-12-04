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
import com.scalar.ledger.database.TamperEvidentAssetbase;
import com.scalar.ledger.emulator.MutableDatabaseEmulator;
import com.scalar.ledger.ledger.Ledger;
import com.scalar.ledger.udf.Function;
import com.scalar.ledger.udf.UdfManager;
import java.io.StringReader;
import java.util.List;
import java.util.Optional;
import javax.inject.Inject;
import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonReader;
import javax.json.JsonString;
import javax.json.JsonValue;
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

  @Inject private MutableDatabaseEmulator databaseEmulator;

  @Inject private UdfManager udfManager;

  @Inject
  public Execute(
      TerminalWrapper terminal,
      ContractManagerEmulator contractManager,
      TamperEvidentAssetbase assetbase,
      Ledger ledger) {
    super(terminal, contractManager, assetbase, ledger);
  }

  @Override
  public void run() {
    JsonObject json = convertJsonParameter(argument);
    if (json != null) {
      executeContract(toKey(id), json);
    }

    JsonObject functionArgumentObject = null;
    if (functionArgument != null) {
      JsonReader jsonReader = Json.createReader(new StringReader(functionArgument));
      functionArgumentObject = jsonReader.readObject();
      jsonReader.close();
    }

    JsonArray udfs = json.getJsonArray("_functions_");
    if (udfs != null) {
      for (JsonValue udf : udfs) {
        String functionName = ((JsonString) udf).getString();
        Function f = udfManager.getInstance(functionName);
        f.invoke(databaseEmulator, json, Optional.ofNullable(functionArgumentObject));
      }
    }
  }
}
