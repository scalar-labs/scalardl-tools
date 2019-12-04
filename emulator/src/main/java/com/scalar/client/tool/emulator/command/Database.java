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
import com.scalar.database.api.Delete;
import com.scalar.database.api.Get;
import com.scalar.database.api.Put;
import com.scalar.database.io.Key;
import com.scalar.database.io.TextValue;
import com.scalar.ledger.database.TamperEvidentAssetbase;
import com.scalar.ledger.emulator.MutableDatabaseEmulator;
import com.scalar.ledger.ledger.Ledger;
import java.util.List;
import javax.inject.Inject;
import javax.json.JsonObject;
import picocli.CommandLine;

@CommandLine.Command(
    name = "database",
    sortOptions = false,
    usageHelpWidth = TerminalWrapper.USAGE_HELP_WIDTH,
    headerHeading = "%n@|bold,underline Usage|@:%n",
    synopsisHeading = "",
    descriptionHeading = "%n@|bold,underline Description|@:%n",
    description = "<TBD>",
    parameterListHeading = "%n@|bold,underline Parameters|@:%n",
    optionListHeading = "%n@|bold,underline Options|@:%n",
    footerHeading = "%n",
    footer = "Usage example: 'database'.%n")
public class Database extends AbstractCommand implements Runnable {
  @CommandLine.Parameters(
      index = "0",
      paramLabel = "method ",
      description = "method that will be execute by MutableDatabase, e.g 'get'")
  private String method;

  @CommandLine.Parameters(
      index = "1",
      paramLabel = "namespace",
      description = "the namespace of the database")
  private String namespace;

  @CommandLine.Parameters(
      index = "2",
      paramLabel = "table_name",
      description = "the table_name of the database")
  private String tableName;

  @CommandLine.Parameters(
      index = "3",
      paramLabel = "asset_id",
      description = "the asset id of th object stored in the database. ")
  private String assetId;

  @CommandLine.Option(
      names = {"-o", "--object"},
      description = "the JSON object that is to be inserted into the MutableDatabase")
  private List<String> object;

  private MutableDatabaseEmulator databaseEmulator;

  @Inject
  public Database(
      MutableDatabaseEmulator databaseEmulator,
      TerminalWrapper terminal,
      ContractManagerEmulator contractManager,
      TamperEvidentAssetbase assetbase,
      Ledger ledger) {
    super(terminal, contractManager, assetbase, ledger);
    this.databaseEmulator = databaseEmulator;
  }

  @Override
  public void run() {
    JsonObject data = convertJsonParameter(object);

    if (method.equals("get")) {
      Get get =
          new Get(new Key(new TextValue("asset_id", assetId)))
              .forNamespace(namespace)
              .forTable(tableName);
      databaseEmulator.get(get);
    } else if (method.equals("delete")) {
      Delete delete =
          new Delete(new Key(new TextValue("asset_id", assetId)))
              .forNamespace(namespace)
              .forTable(tableName);
      databaseEmulator.delete(delete);
    } else if (method.equals("put")) {
      Put put =
          new Put(new Key(new TextValue("asset_id", assetId)))
              .forNamespace(namespace)
              .forTable(tableName);
      if (object != null) {
        put.withValue(new TextValue("data", data.toString()));
      }
      databaseEmulator.put(put);
    }
    // TODO subcommand (scan)
  }
}
