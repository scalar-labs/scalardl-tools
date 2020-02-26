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

import com.scalar.dl.ledger.database.FunctionRegistry;
import com.scalar.dl.ledger.function.FunctionEntry;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

class FunctionRegistryEmulator implements FunctionRegistry {
  private final Map<String, FunctionEntry> functions;

  public FunctionRegistryEmulator() {
    functions = new LinkedHashMap<String, FunctionEntry>();
  }

  @Override
  public void bind(FunctionEntry functionEntry) {
    functions.put(functionEntry.getId(), functionEntry);
  }

  @Override
  public Optional<FunctionEntry> lookup(String id) {
    return Optional.ofNullable(functions.get(id));
  }

  @Override
  public void unbind(String id) {
    functions.remove(id);
  }
}
