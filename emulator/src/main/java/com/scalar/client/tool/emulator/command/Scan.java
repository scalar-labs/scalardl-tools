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
import com.scalar.ledger.database.TransactionalAssetbase;
import com.scalar.ledger.ledger.Ledger;
import javax.inject.Inject;
import javax.json.Json;
import javax.json.JsonObjectBuilder;
import picocli.CommandLine;

@CommandLine.Command(
    name = "scan",
    sortOptions = false,
    usageHelpWidth = TerminalWrapper.USAGE_HELP_WIDTH,
    headerHeading = "%n@|bold,underline Usage|@:%n",
    synopsisHeading = "",
    descriptionHeading = "%n@|bold,underline Description|@:%n",
    description = "Execute the scan contract. This command is equivalent to 'execute scan'.",
    parameterListHeading = "%n@|bold,underline Parameters|@:%n",
    optionListHeading = "%n@|bold,underline Options|@:%n",
    footerHeading = "%n",
    footer =
        "For example:%n"
            + "- 'scan foo'%n"
            + "- 'scan foo --ascending --start 2 --end 5 --limit 2'%n")
public class Scan extends AbstractCommand {

  @CommandLine.Option(
      names = {"-s", "--start"},
      description = "return only assets with age >= start")
  private int start;

  @CommandLine.Option(
      names = {"-e", "--end"},
      description = "return only assets with age < end")
  private int end;

  @CommandLine.Option(
      names = {"-a", "--ascending"},
      description =
          "add this flag to return assets in ascending order. The default order is descending.")
  private boolean ascendingOrder;

  @CommandLine.Option(
      names = {"-l", "--limit"},
      description =
          "an integer > 0 which is the maximum number of assets returned. By default there is no limit")
  private int limit;

  @CommandLine.Parameters(
      index = "0",
      paramLabel = "assetId",
      description = "the asset id of the object on the ledger")
  private String assetId;

  @Inject
  public Scan(
      TerminalWrapper terminal,
      ContractManagerWrapper contractManager,
      TransactionalAssetbase assetbase,
      Ledger ledger) {
    super(terminal, contractManager, assetbase, ledger);
  }

  @Override
  public void run() {
    JsonObjectBuilder argument = Json.createObjectBuilder();
    argument.add("asset_id", assetId);

    if (ascendingOrder) {
      argument.add("asc_order", true);
    }

    if (start >= 0) {
      argument.add("start", start);
    }

    if (end > 0) {
      argument.add("end", end);
    }

    if (limit > 0) {
      argument.add("limit", limit);
    }

    executeContract(toKey("scan"), argument.build());
  }
}
