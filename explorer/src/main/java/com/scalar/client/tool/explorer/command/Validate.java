/*
 * This file is part of the Scalar DL Explorer.
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
 * a commercial license.  For more information, please contact Scalar, Inc.
 */

package com.scalar.client.tool.explorer.command;

import com.scalar.client.tool.explorer.command.Explorer.ExplorerExecutor;
import com.scalar.dl.ledger.service.StatusCode;
import picocli.CommandLine;
import picocli.CommandLine.ParentCommand;

@CommandLine.Command(name = "validate", description = "Validate the specified asset")
public class Validate implements Runnable {
  @CommandLine.Parameters(
      paramLabel = "asset_id",
      description = "The id of the asset to validate",
      arity = "1..*")
  private String[] assetIds;

  @CommandLine.Option(
      names = {"-f", "--file"},
      description =
          "Specify an alternative client.properties file%n(default: client.properties)",
      defaultValue = "client.properties")
  private String file;

  @CommandLine.Option(
      names = {"-h", "--help"},
      usageHelp = true,
      description = "Display this help and exit")
  private boolean help;

  @ParentCommand private Explorer parent;

  public void run() {
    ExplorerExecutor executor =
        explorer -> {
          StatusCode statusCode;
          try {
            for (String assetId : assetIds) {
              statusCode = explorer.validate(assetId).getCode();

              if(statusCode.equals(StatusCode.OK)) {
                  System.out.println(assetId + " is not tampered");
              } else if (statusCode.equals(StatusCode.ASSET_NOT_FOUND)) {
                  System.out.println(assetId + " does not exist");
              } else{
                  System.out.println(statusCode + " : Validation error");
              }
            }
          } catch (Exception e) {
            System.err.println(e.getMessage());
          }
        };
    parent.execute(file, executor);
  }
}
