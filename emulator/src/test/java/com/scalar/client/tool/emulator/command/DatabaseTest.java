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

import com.scalar.client.tool.emulator.TerminalWrapper;
import com.scalar.db.api.Delete;
import com.scalar.db.api.Get;
import com.scalar.db.api.Put;
import com.scalar.db.api.Result;
import com.scalar.db.api.Scan;
import com.scalar.db.io.BlobValue;
import com.scalar.db.io.Key;
import com.scalar.db.io.TextValue;
import com.scalar.db.io.Value;
import com.scalar.dl.ledger.emulator.MutableDatabaseEmulator;
import com.scalar.dl.ledger.emulator.MutableDatabaseResult;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import picocli.CommandLine;

public class DatabaseTest {
  @Mock private MutableDatabaseEmulator backend;
  @Mock private TerminalWrapper terminal;
  private Database database;

  @Before
  public void setUp() {
    MockitoAnnotations.initMocks(this);
    database = new Database(backend, terminal);
  }

  @Test
  public void run_GetWithPartitionKey_ShouldReturnCorrectJSON() {
    // Arrange
    Key partition = new Key(new TextValue("partition", "p"));
    Get get = new Get(partition);
    Map<String, Value> mockedValue = new HashMap<String, Value>();
    mockedValue.put("value", new TextValue("bar"));
    MutableDatabaseResult mockedResult =
        new MutableDatabaseResult(Optional.of(partition), Optional.empty(), mockedValue);
    when(backend.get(get)).thenReturn(Optional.of(mockedResult));

    // Action
    CommandLine.run(database, "get", "-p {\"partition\":\"p\"}");

    // Assert
    String expected = "{\"value\":\"bar\"}";
    verify(terminal).println(expected);
  }

  @Test
  public void run_GetWithPartitionKeyClusteringKey_ShouldReturnCorrectJSON() {
    // Arrange
    Key partition = new Key(new TextValue("partition", "p"));
    Key clustering = new Key(new TextValue("clustering", "c"));
    Get get = new Get(partition, clustering);
    Map<String, Value> mockedValue = new HashMap<String, Value>();
    mockedValue.put("value", new TextValue("bar"));
    MutableDatabaseResult mockedResult =
        new MutableDatabaseResult(Optional.of(partition), Optional.of(clustering), mockedValue);
    when(backend.get(get)).thenReturn(Optional.of(mockedResult));

    // Action
    CommandLine.run(database, "get", "-p {\"partition\":\"p\"}", "-c {\"clustering\":\"c\"}");

    // Assert
    String expected = "{\"value\":\"bar\"}";
    verify(terminal).println(expected);
  }

  @Test
  public void run_PutWithPartitionKey_ShouldSucceed() {
    // Arrange

    // Action
    CommandLine.run(database, "put", "-p {\"partition\":\"p\"}", "-v {\"value\":\"bar\"}");

    // Assert
    Key partition = new Key(new TextValue("partition", "p"));
    Put expected = (new Put(partition)).withValue(new TextValue("value", "bar"));
    verify(backend).put(expected);
  }

  @Test
  public void run_PutWithPartitionKeyClusteringKey_ShouldSucceed() {
    // Arrange

    // Action
    CommandLine.run(
        database,
        "put",
        "-p {\"partition\":\"p\"}",
        "-c {\"clustering\": \"c\"}",
        "-v {\"value\":\"bar\"}");

    // Assert
    Key partition = new Key(new TextValue("partition", "p"));
    Key clustering = new Key(new TextValue("clustering", "c"));
    Put expected = (new Put(partition, clustering)).withValue(new TextValue("value", "bar"));
    verify(backend).put(expected);
  }

  @Test
  public void run_DeleteWithPartitionKey_ShouldSucceed() {
    // Arrange

    // Action
    CommandLine.run(database, "delete", "-p {\"partition\":\"p\"}");

    // Assert
    Key partition = new Key(new TextValue("partition", "p"));
    Delete expected = new Delete(partition);
    verify(backend).delete(expected);
  }

  @Test
  public void run_DeleteWithPartitionKeyClusteringKey_ShouldSucceed() {
    // Arrange

    // Action
    CommandLine.run(database, "delete", "-p {\"partition\":\"p\"}", "-c {\"clustering\": \"c\"}");

    // Assert
    Key partition = new Key(new TextValue("partition", "p"));
    Key clustering = new Key(new TextValue("clustering", "c"));
    Delete expected = new Delete(partition, clustering);
    verify(backend).delete(expected);
  }

  @Test
  public void run_ScanWithPartitionKey_ShouldSucceed() {
    // Arrange
    Key partition = new Key(new TextValue("partition", "p"));
    Scan scan = new Scan(partition);
    Map<String, Value> mockedValue = new HashMap<String, Value>();
    mockedValue.put("value", new TextValue("bar"));
    List<Result> mockedResultList = new ArrayList<Result>();
    mockedResultList.add(
        new MutableDatabaseResult(Optional.of(partition), Optional.empty(), mockedValue));
    when(backend.scan(scan)).thenReturn(mockedResultList);

    // Action
    CommandLine.run(database, "scan", "-p {\"partition\":\"p\"}");

    // Assert
    String expected = "[{\"value\":\"bar\"}]";
    verify(terminal).println(expected);
  }

  @Test
  public void run_GetWithUnsupportedData_ShouldGetHintMessage() {
    // Arrange

    // Action
    CommandLine.run(database, "get", "-p {\"partition\":{\"nested\":\"p\"}}");

    // Assert
    String expected = "Structured data is not supported";
    verify(terminal).println(expected);
  }

  @Test
  public void run_GetBlobData_ShouldDisplayCorrectly() {
    // Arrange
    Key partition = new Key(new TextValue("partition", "p"));
    Get get = new Get(partition);
    Map<String, Value> mockedValue = new HashMap<String, Value>();
    byte[] blob = "bar".getBytes();
    mockedValue.put("value", new BlobValue(blob));
    MutableDatabaseResult mockedResult =
        new MutableDatabaseResult(Optional.of(partition), Optional.empty(), mockedValue);
    when(backend.get(get)).thenReturn(Optional.of(mockedResult));

    // Action
    CommandLine.run(database, "get", "-p {\"partition\":\"p\"}");

    // Assert
    String expected = "{\"value\":\"data:;base64,YmFy\"}";
    verify(terminal).println(expected);
  }
}
