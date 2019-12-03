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

import com.scalar.client.tool.emulator.ContractManagerEmulator;
import com.scalar.client.tool.emulator.TerminalWrapper;
import com.scalar.ledger.contract.Contract;
import com.scalar.ledger.contract.ContractEntry;
import com.scalar.ledger.crypto.CertificateEntry;
import com.scalar.ledger.database.TamperEvidentAssetbase;
import com.scalar.ledger.ledger.Ledger;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonReader;
import javax.json.JsonWriter;
import javax.json.JsonWriterFactory;
import javax.json.stream.JsonGenerator;
import picocli.CommandLine;

public abstract class AbstractCommand implements Runnable {
  TerminalWrapper terminal;
  ContractManagerEmulator contractManager;
  TamperEvidentAssetbase assetbase;
  Ledger ledger;

  @CommandLine.Option(
      names = {"-h", "--help"},
      description = "print the help and exit",
      usageHelp = true)
  private boolean help;

  public AbstractCommand(
      TerminalWrapper terminal,
      ContractManagerEmulator contractManager,
      TamperEvidentAssetbase assetbase,
      Ledger ledger) {
    this.terminal = terminal;
    this.contractManager = contractManager;
    this.assetbase = assetbase;
    this.ledger = ledger;
  }

  void executeContract(ContractEntry.Key key, JsonObject argument) {
    Contract contract = contractManager.getInstance(key.getId());
    JsonObject response =
        contract.invoke(this.ledger, argument, contractManager.get(key).getProperties());
    this.assetbase.commit();
    if (response != null) {
      Map<String, Object> properties = new HashMap<>();
      properties.put(JsonGenerator.PRETTY_PRINTING, true);
      JsonWriterFactory writerFactory = Json.createWriterFactory(properties);
      StringWriter stringWriter = new StringWriter();
      JsonWriter jsonWriter = writerFactory.createWriter(stringWriter);
      jsonWriter.writeObject(response);
      terminal.println(stringWriter.toString());
    }
  }

  ContractEntry.Key toKey(String id) {
    return new ContractEntry.Key(id, new CertificateEntry.Key("emulator_user", 0));
  }

  JsonObject convertJsonParameter(List<String> values) {
    String text = values.stream().reduce("", (a, b) -> a = a + " " + b);
    try {
      if (text.contains(File.separator)) {
        text = new String(Files.readAllBytes(new File(text).toPath()));
      }

      JsonReader reader = Json.createReader(new StringReader(text));
      return reader.readObject();
    } catch (IOException e) {
      terminal.println("Error parsing json parameter: " + text);
      terminal.println(e.toString());
    }
    return null;
  }
}
