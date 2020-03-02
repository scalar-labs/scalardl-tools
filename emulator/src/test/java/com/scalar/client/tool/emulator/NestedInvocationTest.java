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
package com.scalar.client.tool.emulator;

import static org.assertj.core.api.Assertions.assertThat;

import com.scalar.dl.ledger.contract.Contract;
import com.scalar.dl.ledger.contract.ContractEntry;
import com.scalar.dl.ledger.crypto.CertificateEntry;
import com.scalar.dl.ledger.database.AssetLedger;
import com.scalar.dl.ledger.database.Ledger;
import com.scalar.dl.ledger.emulator.AssetbaseEmulator;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import javax.json.Json;
import javax.json.JsonObject;
import org.junit.Before;
import org.junit.Test;

public class NestedInvocationTest {
  private static final String CONTRACT_ID_ATTRIBUTE_NAME = "contract_id";
  private Ledger ledger;
  private ContractManagerEmulator contractManager;

  @Before
  public void setUp() {
    ledger = new AssetLedger(new AssetbaseEmulator());
    contractManager = new ContractManagerEmulator(new ContractRegistryEmulator());

    registerContract("caller", "Caller");
    registerContract("callee", "Callee");
    System.out.println("test");
  }

  private void registerContract(String id, String name) {
    Path parent =
        Paths.get(
            "build", "classes", "java", "test", "com", "scalar", "client", "tool", "emulator");
    contractManager.register(
        id,
        "com.scalar.client.tool.emulator." + name,
        new File(parent.toFile(), name + ".class"),
        null);
  }

  @Test
  public void invoke_NestedInvocation_ShouldExecuteBothContracts() {
    // Arrange
    ContractEntry.Key key =
        new ContractEntry.Key("caller", new CertificateEntry.Key("emulator_user", 0));
    Contract contract = contractManager.getInstance(key.getId());
    JsonObject argument =
        Json.createObjectBuilder().add(CONTRACT_ID_ATTRIBUTE_NAME, "callee").build();

    // Action
    JsonObject result = contract.invoke(ledger, argument, Optional.empty());

    // Act assert
    assertThat(result.getBoolean("caller_is_called")).isTrue();
    assertThat(result.getBoolean("callee_is_called")).isTrue();
  }
}
