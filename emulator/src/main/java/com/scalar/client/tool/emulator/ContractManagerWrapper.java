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

import com.scalar.ledger.contract.Contract;
import com.scalar.ledger.contract.ContractEntry;
import com.scalar.ledger.contract.ContractManager;
import com.scalar.ledger.crypto.CertificateEntry;
import com.scalar.ledger.exception.RegistryIOException;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import javax.json.JsonObject;

public class ContractManagerWrapper {
  private ContractManager manager;

  public ContractManagerWrapper(ContractManager manager) {
    this.manager = manager;
  }

  public void register(String id, String name, File file, JsonObject properties) {
    try {
      byte[] contract = Files.readAllBytes(file.toPath());
      register(toContractEntry(id, name, contract, properties));
    } catch (IOException e) {
      throw new RegistryIOException("could not register contract " + id);
    }
  }

  private ContractEntry toContractEntry(
      String id, String name, byte[] contract, JsonObject properties) {
    return new ContractEntry(
        id,
        name,
        "holder_id",
        1,
        contract,
        properties,
        System.currentTimeMillis(),
        "signature".getBytes());
  }

  public void register(ContractEntry entry) {
    manager.register(entry);
  }

  public ContractEntry get(ContractEntry.Key key) {
    return manager.get(key.getId());
  }

  public Contract getInstance(ContractEntry.Key key) {
    return manager.getInstance(key.getId());
  }

  public List<ContractEntry> scan() {
    CertificateEntry.Key certKey = new CertificateEntry.Key("emulator_user", 0);
    return manager.scan(certKey);
  }
}
