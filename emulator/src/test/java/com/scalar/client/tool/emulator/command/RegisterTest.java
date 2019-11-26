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

import static org.mockito.Mockito.verify;

import com.scalar.client.tool.emulator.ContractManagerWrapper;
import com.scalar.client.tool.emulator.TerminalWrapper;
import com.scalar.ledger.database.TamperEvidentAssetbase;
import com.scalar.ledger.emulator.AssetbaseEmulator;
import com.scalar.ledger.ledger.Ledger;
import java.io.File;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import picocli.CommandLine;

public class RegisterTest {
  private static final String CONTRACT_ID = "id";
  private static final String CONTRACT_NAME = "name";
  private static final String CONTRACT_FILE = "file";
  private Register register;
  private TamperEvidentAssetbase assetbase;
  @Mock TerminalWrapper terminal;
  @Mock ContractManagerWrapper contractManager;
  @Mock Ledger ledger;

  @Before
  public void setUp() {
    MockitoAnnotations.initMocks(this);
    assetbase = new AssetbaseEmulator();
    register = new Register(terminal, contractManager, assetbase, ledger);
  }

  @Test
  public void run_ProperContractGiven_ShouldRegisterSuccessfully() {
    // Act
    CommandLine.run(register, CONTRACT_ID, CONTRACT_NAME, CONTRACT_FILE);

    verify(contractManager).register(CONTRACT_ID, CONTRACT_NAME, new File(CONTRACT_FILE), null);
  }
}
