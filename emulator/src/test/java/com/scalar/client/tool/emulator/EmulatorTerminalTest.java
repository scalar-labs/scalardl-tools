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

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class EmulatorTerminalTest {
  @Mock private ContractManagerEmulator contractManagerEmulator;
  @Mock private TerminalWrapper terminal;
  private EmulatorTerminal emulatorTerminal;

  @Before
  public void setUp() {
    MockitoAnnotations.initMocks(this);
    emulatorTerminal = new EmulatorTerminal(terminal, contractManagerEmulator);
  }

  @Test
  public void input_WithJsonInput_ShouldReturnProperParamsArrayProperly() {
    // Arrange
    String input = "put {\"asset_id\": \"X\", \"data\": {\"alice\": 100, \"bob\": 0}}";
    String[] mockedParamsArray = {
      "put", "{\"asset_id\": \"X\", \"data\": {\"alice\": 100, \"bob\": 0}}"
    };

    // Action
    String[] paramsArray = emulatorTerminal.paramsParser(input);

    // Assert
    Assert.assertArrayEquals(paramsArray, mockedParamsArray);
  }

  @Test
  public void input_WithSpacesInput_ShouldReturnProperParamsArrayProperly() {
    // Arrange
    String input = "put \"key with space\" {\"alice\": 100, \"bob\": 0}";
    String[] mockedParamsArray = {"put", "key with space", "{\"alice\": 100, \"bob\": 0}"};

    // Action
    String[] paramsArray = emulatorTerminal.paramsParser(input);

    // Assert
    Assert.assertArrayEquals(paramsArray, mockedParamsArray);
  }

  @Test
  public void input_WithSpacesForFlagInput_ShouldReturnProperParamsArrayProperly() {
    // Arrange
    String input =
        "database -c clusterKey -p \"partition key\" -v \"value with space\" "
            + "-n \"namespace of database\" -t \"table with space\"";
    String[] mockedParamsArray = {
      "database",
      "-c",
      "clusterKey",
      "-p",
      "partition key",
      "-v",
      "value with space",
      "-n",
      "namespace of database",
      "-t",
      "table with space"
    };

    // Action
    String[] paramsArray = emulatorTerminal.paramsParser(input);

    // Assert
    Assert.assertArrayEquals(paramsArray, mockedParamsArray);
  }
}
