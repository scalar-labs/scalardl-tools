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
import org.junit.BeforeClass;
import org.junit.Test;

public class NestedInvocationTest {
  private static final String CONTRACT_ID_ATTRIBUTE_NAME = "contract_id";
  private static Ledger ledger;
  private static ContractManagerEmulator contractManager;

  @BeforeClass
  public static void setUp() {
    ledger = new AssetLedger(new AssetbaseEmulator());
    contractManager = new ContractManagerEmulator(new ContractRegistryEmulator());

    registerContract("caller", "Caller");
    registerContract("callee", "Callee");
  }

  public static void registerContract(String id, String name) {
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
  public void invoke_NestedInvocationWithoutSettingCertificate_ShouldExecuteBothContracts() {
    // Arrange
    ContractEntry.Key key =
        new ContractEntry.Key("caller", new CertificateEntry.Key("emulator_user", 0));
    Contract contract = contractManager.getInstance(key.getId());
    JsonObject argument =
        Json.createObjectBuilder().add(CONTRACT_ID_ATTRIBUTE_NAME, "callee").build();

    // Act
    JsonObject result = contract.invoke(ledger, argument, Optional.empty());

    // Assert
    assertThat(result.getBoolean("caller_is_called")).isTrue();
    assertThat(result.getBoolean("caller_is_root")).isTrue();
    assertThat(result.getBoolean("callee_is_called")).isTrue();
    assertThat(result.getBoolean("callee_is_root")).isFalse();
    assertCertificate(
        result.getJsonObject("callee_certificate"), ContractManagerEmulator.defaultCertificateKey);
    assertCertificate(
        result.getJsonObject("caller_certificate"), ContractManagerEmulator.defaultCertificateKey);
  }

  @Test
  public void invoke_NestedInvocationWithSettingCertificate_ShouldExecuteBothContracts() {
    // Arrange
    CertificateEntry.Key certificate = new CertificateEntry.Key("foo", 3);
    contractManager.setEmulatedCertificateKey(certificate);
    ContractEntry.Key key =
        new ContractEntry.Key("caller", new CertificateEntry.Key("emulator_user", 0));
    Contract contract = contractManager.getInstance(key.getId());
    JsonObject argument =
        Json.createObjectBuilder().add(CONTRACT_ID_ATTRIBUTE_NAME, "callee").build();

    // Act
    JsonObject result = contract.invoke(ledger, argument, Optional.empty());

    // Assert
    assertCertificate(result.getJsonObject("callee_certificate"), certificate);
    assertCertificate(result.getJsonObject("caller_certificate"), certificate);
  }

  private void assertCertificate(JsonObject result, CertificateEntry.Key expected) {
    assertThat(result).isNotNull();
    assertThat(result.getString("holder_id")).isEqualTo(expected.getHolderId());
    assertThat(result.getInt("version")).isEqualTo(expected.getVersion());
  }
}
