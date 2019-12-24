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
import com.scalar.ledger.udf.UdfEntry;
import com.scalar.ledger.udf.UdfManager;
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
  @Mock UdfManager manager;
  @Mock File udfFile;
  @Mock TerminalWrapper terminal;
  RegisterFunction registerFunction;
  File udf;

  @Before
  public void setUp() throws IOException {
    MockitoAnnotations.initMocks(this);
    registerFunction = new RegisterFunction(terminal, manager);
    udf = File.createTempFile("udf", ".class");
  }

  @Test
  public void run_ProperContractGiven_ShouldRegisterSuccessfully() throws IOException {
    // Arrange
    String id = "id";
    String canonicalName = "canonical";
    byte[] bytes = Files.readAllBytes(udf.toPath());

    // Act
    CommandLine.run(registerFunction, id, canonicalName, udf.getAbsolutePath());

    // Assert
    verify(manager).register(argThat(new UdfEntryMatcher(id, canonicalName, bytes)));
  }

  @After
  public void tearDown() {
    udf.deleteOnExit();
  }

  class UdfEntryMatcher implements ArgumentMatcher<UdfEntry> {
    private String id;
    private String canonicalName;
    private byte[] bytes;

    public UdfEntryMatcher(String id, String canonicalName, byte[] bytes) {
      this.id = id;
      this.canonicalName = canonicalName;
      this.bytes = bytes;
    }

    @Override
    public boolean matches(UdfEntry right) {
      return right.getId().equals(this.id)
          && right.getBinaryName().equals(this.canonicalName)
          && Arrays.equals(this.bytes, right.getByteCode());
    }
  }
}
