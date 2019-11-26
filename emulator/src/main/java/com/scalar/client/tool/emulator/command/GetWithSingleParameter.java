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

import com.scalar.client.tool.emulator.ContractManagerWrapper;
import com.scalar.client.tool.emulator.TerminalWrapper;
import com.scalar.ledger.database.TamperEvidentAssetbase;
import com.scalar.ledger.ledger.Ledger;
import java.util.List;
import javax.inject.Inject;
import javax.json.JsonObject;
import picocli.CommandLine;

@CommandLine.Command(
    name = "get -j",
    sortOptions = false,
    usageHelpWidth = TerminalWrapper.USAGE_HELP_WIDTH,
    headerHeading = "%n@|bold,underline Usage|@:%n",
    synopsisHeading = "",
    descriptionHeading = "%n@|bold,underline Description|@:%n",
    description = "Execute the get contract. This command is equivalent to 'execute get'.",
    parameterListHeading = "%n@|bold,underline Parameters|@:%n",
    optionListHeading = "%n@|bold,underline Options|@:%n",
    footerHeading = "%n",
    footer =
        "The JSON argument format is {\"@|fg(blue) asset_id|@\":\"<an_asset_id>\"}.%n"
            + "For example: 'get -j {\"asset_id\":\"foo\"}'.%n")
public class GetWithSingleParameter extends AbstractCommand {

  @CommandLine.Parameters(
      arity = "1..*",
      paramLabel = "argument",
      description =
          "the JSON contract argument. A plain text JSON object or the path to a file containing a JSON object")
  private List<String> argument;

  @Inject
  public GetWithSingleParameter(
      TerminalWrapper terminal,
      ContractManagerWrapper contractManager,
      TamperEvidentAssetbase assetbase,
      Ledger ledger) {
    super(terminal, contractManager, assetbase, ledger);
  }

  @Override
  public void run() {
    JsonObject json = convertJsonParameter(argument);
    if (json != null) {
      executeContract(toKey("get"), json);
    }
  }
}
