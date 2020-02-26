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
import static org.mockito.Mockito.when;

import com.scalar.client.tool.emulator.ContractManagerEmulator;
import com.scalar.client.tool.emulator.TerminalWrapper;
import com.scalar.dl.ledger.contract.Contract;
import com.scalar.dl.ledger.contract.ContractEntry;
import com.scalar.dl.ledger.crypto.CertificateEntry;
import com.scalar.dl.ledger.database.Ledger;
import com.scalar.dl.ledger.emulator.AssetbaseEmulator;
import com.scalar.dl.ledger.emulator.MutableDatabaseEmulator;
import com.scalar.dl.ledger.function.Function;
import com.scalar.dl.ledger.function.FunctionManager;
import java.util.Optional;
import javax.json.Json;
import javax.json.JsonObject;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import picocli.CommandLine;

public class ExecuteTest {
  private static final String CONTRACT_ID = "contract";
  private Execute execute;
  private AssetbaseEmulator assetbase;
  @Mock private Contract contract;
  @Mock private Function function;
  @Mock private ContractManagerEmulator contractManager;
  @Mock private Ledger ledger;
  @Mock private TerminalWrapper terminal;
  @Mock private FunctionManager functionManager;
  @Mock private MutableDatabaseEmulator database;

  @Before
  public void setUp() {
    MockitoAnnotations.initMocks(this);
    assetbase = new AssetbaseEmulator();
    execute = new Execute(terminal, contractManager, assetbase, ledger, functionManager, database);
  }

  @Test
  public void run_ExecuteContract_ShouldCallInvokeOnTheContract() {
    // Arrange
    JsonObject argument = Json.createObjectBuilder().add("x", "y").build();
    ContractEntry.Key key =
        new ContractEntry.Key(CONTRACT_ID, new CertificateEntry.Key("emulator_user", 0));
    ContractEntry entry =
        new ContractEntry(
            "id",
            "binaryName",
            "cert_holder_id",
            1,
            "contract".getBytes(),
            null,
            1,
            "signature".getBytes());
    when(contractManager.get(key)).thenReturn(entry);
    when(contractManager.getInstance(key.getId())).thenReturn(contract);
    when(contract.invoke(ledger, argument, Optional.empty())).thenReturn(null);

    // Act
    CommandLine.run(execute, CONTRACT_ID, argument.toString());

    // Assert
    verify(contract).invoke(ledger, argument, Optional.empty());
  }

  @Test
  public void run_ExecuteContractWithFunction_ShouldSucceed() {
    // Arrange
    JsonObject contractArgument =
        Json.createObjectBuilder()
            .add("_functions_", Json.createArrayBuilder().add("function_foo"))
            .build();
    JsonObject functionArgument = Json.createObjectBuilder().add("foo", "bar").build();
    ContractEntry.Key key =
        new ContractEntry.Key(CONTRACT_ID, new CertificateEntry.Key("emulator_user", 0));
    ContractEntry entry =
        new ContractEntry(
            "id",
            "binaryName",
            "cert_holder_id",
            1,
            "contract".getBytes(),
            null,
            1,
            "signature".getBytes());
    when(contractManager.get(key)).thenReturn(entry);
    when(contractManager.getInstance(key.getId())).thenReturn(contract);
    when(functionManager.getInstance("function_foo")).thenReturn(function);

    // Act
    CommandLine.run(
        execute, CONTRACT_ID, contractArgument.toString(), "-fa=" + functionArgument.toString());

    // Assert
    verify(contract).invoke(ledger, contractArgument, Optional.empty());
    verify(function)
        .invoke(database, Optional.of(functionArgument), contractArgument, Optional.empty());
  }

  @Test
  public void run_ExecuteContractWithFunctionNoArgument_ShouldSucceed() {
    // Arrange
    JsonObject contractArgument =
        Json.createObjectBuilder()
            .add("_functions_", Json.createArrayBuilder().add("function_foo"))
            .build();
    ContractEntry.Key key =
        new ContractEntry.Key(CONTRACT_ID, new CertificateEntry.Key("emulator_user", 0));
    ContractEntry entry =
        new ContractEntry(
            "id",
            "binaryName",
            "cert_holder_id",
            1,
            "contract".getBytes(),
            null,
            1,
            "signature".getBytes());
    when(contractManager.get(key)).thenReturn(entry);
    when(contractManager.getInstance(key.getId())).thenReturn(contract);
    when(functionManager.getInstance("function_foo")).thenReturn(function);

    // Act
    CommandLine.run(execute, CONTRACT_ID, contractArgument.toString());

    // Assert
    verify(contract).invoke(ledger, contractArgument, Optional.empty());
    verify(function).invoke(database, Optional.empty(), contractArgument, Optional.empty());
  }
}
