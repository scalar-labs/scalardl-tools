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

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;

import com.scalar.client.tool.emulator.TerminalWrapper;
import com.scalar.dl.ledger.function.FunctionEntry;
import com.scalar.dl.ledger.function.FunctionManager;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import picocli.CommandLine;

public class RegisterFunctionTest {
  @Mock FunctionManager manager;
  @Mock TerminalWrapper terminal;
  RegisterFunction registerFunction;
  File file;

  @Before
  public void setUp() throws IOException {
    MockitoAnnotations.initMocks(this);
    registerFunction = new RegisterFunction(terminal, manager);
    file = File.createTempFile("function", ".class");
  }

  @Test
  public void run_ProperContractGiven_ShouldRegisterSuccessfully() throws IOException {
    // Arrange
    String id = "id";
    String canonicalName = "canonical";
    byte[] bytes = Files.readAllBytes(file.toPath());

    // Act
    CommandLine.run(registerFunction, id, canonicalName, file.getAbsolutePath());

    // Assert
    verify(manager).register(argThat(new FunctionEntryMatcher(id, canonicalName, bytes)));
  }

  @After
  public void tearDown() {
    file.deleteOnExit();
  }

  class FunctionEntryMatcher implements ArgumentMatcher<FunctionEntry> {
    private String id;
    private String canonicalName;
    private byte[] bytes;

    public FunctionEntryMatcher(String id, String canonicalName, byte[] bytes) {
      this.id = id;
      this.canonicalName = canonicalName;
      this.bytes = bytes;
    }

    @Override
    public boolean matches(FunctionEntry right) {
      return right.getId().equals(this.id)
          && right.getBinaryName().equals(this.canonicalName)
          && Arrays.equals(this.bytes, right.getByteCode());
    }
  }
}
