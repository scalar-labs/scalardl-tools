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

import com.scalar.dl.ledger.contract.ContractEntry;
import com.scalar.dl.ledger.database.ContractRegistry;
import com.scalar.dl.ledger.exception.MissingContractException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ContractRegistryEmulator implements ContractRegistry {
  private final Map<String, ContractEntry> contracts;

  public ContractRegistryEmulator() {
    contracts = new LinkedHashMap<>();
  }

  @Override
  public void bind(ContractEntry entry) {
    contracts.put(entry.getId(), entry);
  }

  @Override
  public void unbind(ContractEntry.Key key) {
    contracts.remove(key.getId());
  }

  public ContractEntry lookup(String id) {
    if (contracts.containsKey(id)) {
      return contracts.get(id);
    }
    throw new MissingContractException("Contract " + id + " has not been registered");
  }

  @Override
  public ContractEntry lookup(ContractEntry.Key key) {
    return lookup(key.getId());
  }

  @Override
  public List<ContractEntry> scan(String certId) {
    return scan(certId, 0);
  }

  @Override
  public List<ContractEntry> scan(String certId, int certVersion) {
    // emulator assumes all contracts are registered to the same user
    return new ArrayList<>(contracts.values());
  }
}
