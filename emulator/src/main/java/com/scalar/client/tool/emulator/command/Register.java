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

import com.scalar.client.tool.emulator.ContractManagerWrapper;
import com.scalar.client.tool.emulator.TerminalWrapper;
import com.scalar.ledger.database.TransactionalAssetbase;
import com.scalar.ledger.ledger.Ledger;
import java.io.File;
import java.util.List;
import javax.inject.Inject;
import javax.json.JsonObject;
import picocli.CommandLine;

@CommandLine.Command(
    name = "register",
    sortOptions = false,
    usageHelpWidth = TerminalWrapper.USAGE_HELP_WIDTH,
    headerHeading = "%n@|bold,underline Usage|@:%n",
    synopsisHeading = "",
    descriptionHeading = "%n@|bold,underline Description|@:%n",
    description = "Register a contract",
    parameterListHeading = "%n@|bold,underline Parameters|@:%n",
    optionListHeading = "%n@|bold,underline Options|@:%n",
    footerHeading = "%n",
    footer =
        "For example: 'register get com.scalar.client.tool.emulator.contract.GetContract ./build/classes/java/main/com/scalar/client/tool/emulator/contract/GetContract.class'%n")
public class Register extends AbstractCommand implements Runnable {
  @CommandLine.Parameters(
      index = "0",
      paramLabel = "id",
      description =
          "id that will be used when executing the contract with the execute command, e.g. 'get'")
  private String id;

  @CommandLine.Parameters(
      index = "1",
      paramLabel = "name",
      description = "contract canonical name, e.g. 'com.scalar.client.tool.emulator.contract.Get'")
  private String name;

  @CommandLine.Parameters(
      index = "2",
      paramLabel = "file",
      description =
          "compiled contract class file, e.g. 'build/java/main/com/scalar/client/tool/emulator/contract/Get.class'")
  private File contractFile;

  @CommandLine.Parameters(
      index = "3..*",
      paramLabel = "contract_property",
      description =
          "the JSON contract property. A plain text JSON object or the path to a file containing a JSON object. For example: {\"x\": \"y\"}")
  private List<String> properties;

  @Inject
  public Register(
      TerminalWrapper terminal,
      ContractManagerWrapper contractManager,
      TransactionalAssetbase assetbase,
      Ledger ledger) {
    super(terminal, contractManager, assetbase, ledger);
  }

  @Override
  public void run() {
    checkArgument(id != null, "id cannot be null");
    checkArgument(name != null, "name cannot be null");
    checkArgument(contractFile != null, "contractFile cannot be null");

    JsonObject json = null;
    if (properties != null) {
      json = convertJsonParameter(properties);
    }
    contractManager.register(id, name, contractFile, json);
    terminal.println("Contract '" + id + "' successfully registered");
  }
}
