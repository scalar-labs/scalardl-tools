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
import com.scalar.dl.ledger.crypto.CertificateEntry;
import com.scalar.dl.ledger.database.Ledger;
import com.scalar.dl.ledger.database.TamperEvidentAssetbase;
import javax.inject.Inject;
import picocli.CommandLine;

@CommandLine.Command(
    name = "set-certificate",
    sortOptions = false,
    usageHelpWidth = TerminalWrapper.USAGE_HELP_WIDTH,
    headerHeading = "%n@|bold,underline Usage|@:%n",
    synopsisHeading = "",
    descriptionHeading = "%n@|bold,underline Description|@:%n",
    description =
        "This is used to emulate which user is executing the registered contract by setting the 'holder_id' and 'version' values "
            + "of the CertificateEntry.Key object returned when a contract invokes the 'getCertificate()' method.\n\n"
            + "By default, the emulator will set the holderId value to 'default_holder_id' and the version to '1'.",
    parameterListHeading = "%n@|bold,underline Parameters|@:%n",
    optionListHeading = "%n@|bold,underline Options|@:%n",
    footerHeading = "%n",
    footer =
        "For example, to emulate the user called 'foo' using the version '1' of the certificate, you need to run "
            + "the command 'set-certificate foo 1'%n")
public class SetCertificate extends AbstractCommand {
  @CommandLine.Parameters(
      index = "0",
      paramLabel = "holder_id",
      description = "the 'holder_id' tied with the certificate")
  private String holderId;

  @CommandLine.Parameters(
      index = "1",
      paramLabel = "version",
      description = "the certificate version")
  private int version;

  @Inject
  public SetCertificate(
      TerminalWrapper terminal,
      ContractManagerEmulator contractManager,
      TamperEvidentAssetbase assetbase,
      Ledger ledger) {
    super(terminal, contractManager, assetbase, ledger);
  }

  @Override
  public void run() {
    contractManager.setEmulatedCertificateKey(new CertificateEntry.Key(holderId, version));
  }
}
