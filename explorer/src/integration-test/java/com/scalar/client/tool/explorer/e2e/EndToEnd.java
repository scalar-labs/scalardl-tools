/*
 * This file is part of the Scalar DL Explorer.
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
 * a commercial license.  For more information, please contact Scalar, Inc.
 */

package com.scalar.client.tool.explorer.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.scalar.dl.client.config.ClientConfig;
import com.scalar.dl.client.service.ClientModule;
import com.scalar.dl.client.service.ClientService;
import com.scalar.client.tool.explorer.command.Explorer;
import com.scalar.client.tool.explorer.e2e.contract.PutContract;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonReader;
import javax.json.JsonStructure;
import javax.json.JsonValue;
import javax.json.stream.JsonParsingException;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;
import picocli.CommandLine;

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class EndToEnd {
  private static final String USERNAME = "cassandra";
  private static final String PASSWORD = "cassandra";
  private static String propertiesFilePath =
      Paths.get("src", "integration-test", "resources", "client.properties").toString();

  private static ClientConfig clientConfig;
  private static ClientService clientService;
  private static String putContractId;

  @BeforeClass
  public static void setUpOnce() throws IOException {
    createService();
    clientService.registerCertificate();
    registerContract();
    createAsset();
  }

  private static void createService() throws IOException {
    clientConfig = new ClientConfig(new File(propertiesFilePath));
    Injector injector = Guice.createInjector(new ClientModule(clientConfig));
    clientService = injector.getInstance(ClientService.class);
  }

  private static void registerContract() {
    putContractId = clientConfig.getCertHolderId() + "PUT";
    String contractName = "com.scalar.client.tool.explorer.e2e.contract.PutContract";
    String contractFilePath =
        Paths.get(
                "build",
                "classes",
                "java",
                "integrationTest",
                "com",
                "scalar",
                "client",
                "tool",
                "explorer",
                "e2e",
                "contract",
                "PutContract.class")
            .toString();
    clientService.registerContract(putContractId, contractName, contractFilePath, Optional.empty());
  }

  private static void createAsset() {
    clientService.executeContract(putContractId, createBookArgument("book-A"));
    clientService.executeContract(putContractId, createBookArgument("book-B"));
    clientService.executeContract(putContractId, createBookArgument("book-C"));
    clientService.executeContract(putContractId, createBookArgument("book-D"));
    clientService.executeContract(putContractId, createBookArgument("book-E"));
  }

  private static JsonObject createBookArgument(String name) {
    return Json.createObjectBuilder()
        .add(PutContract.NAME, name)
        .add(PutContract.ID, "book")
        .build();
  }

  @AfterClass
  public static void closeClientService() {
    clientService.close();
  }

  @AfterClass
  public static void truncateTables() throws IOException, InterruptedException {
    ProcessBuilder builder =
        new ProcessBuilder(
            "cqlsh",
            "-u",
            USERNAME,
            "-p",
            PASSWORD,
            "-e",
            "TRUNCATE scalar.asset;" + "TRUNCATE scalar.asset_metadata;");
    Process process = builder.start();
    int ret = process.waitFor();
    if (ret != 0) {
      Assert.fail("TRUNCATE TABLE failed.");
    }
  }

  private String getOutput(String... args) throws UnsupportedEncodingException {
    String[] commandArgs = args.length != 0 ? args : new String[] {"-h"};
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (PrintStream ps = new PrintStream(output, true, "UTF-8")) {
      PrintStream previousOut = System.out;
      System.setOut(ps);
      CommandLine.run(new Explorer(), ps, commandArgs);
      System.setOut(previousOut);
    }
    return output.toString();
  }

  private JsonStructure parse(String output) {
    JsonStructure result;
    try {
      JsonReader jsonReader = Json.createReader(new StringReader(output));
      result = jsonReader.read();
      jsonReader.close();
    } catch (JsonParsingException e) {
      result = null;
    }
    return result;
  }

  private JsonObject setAssetBase(JsonObject json, Object[] obj) {
    int age = (Integer) obj[0];
    String name = obj[1].toString();
    String inputId = obj[2].toString();

    JsonObject data =
        Json.createObjectBuilder()
            .add("id", "book")
            .add("name", name)
            .build();

    JsonObject book =
        Json.createObjectBuilder()
            .add("age", age <= 0 ? 0 : age - 1)
            .add(
                "data",
                Json.createObjectBuilder()
                    .add("id", "book")
                    .add("name", obj[2].toString())
                    .build())
            .build();

    JsonObject input;
    if (inputId.equals("")) {
      input = Json.createObjectBuilder().build();
    } else {
      input = Json.createObjectBuilder().add("book", book).build();
    }

    JsonObject argument =
        Json.createObjectBuilder()
            .add("id", "book")
            .add("name", name)
            .add("nonce", json.getJsonObject("argument").getString("nonce"))
            .build();

    return Json.createObjectBuilder()
        .add("id", "book")
        .add("age", age)
        .add("data", data)
        .add("input", input)
        .add("contract_id", clientConfig.getCertHolderId() + "PUT")
        .add("argument", argument)
        .add("signature", json.getString("signature"))
        .add("prev_hash", json.getString("prev_hash"))
        .add("hash", json.getString("hash"))
        .build();
  }

  private JsonArray setScanExpected(JsonArray result, List<Object[]> list) {
    int i = 0;
    JsonArrayBuilder builder = Json.createArrayBuilder();
    for (JsonValue obj : result) {
      JsonObject asset = setAssetBase(obj.asJsonObject(), list.get(i));
      builder.add(asset);
      i++;
    }

    return builder.build();
  }

  private JsonObject contractsExpected(JsonStructure result) {
    JsonObject object = (JsonObject) result;
    JsonObject inPutContract =
        Json.createObjectBuilder()
            .add("contract_name", "com.scalar.client.tool.explorer.e2e.contract.PutContract")
            .add("cert_id", clientConfig.getCertHolderId())
            .add("cert_version", 1)
            .add("contract_properties", Json.createObjectBuilder().build())
            .add(
                "registered_at",
                object
                    .getJsonObject(clientConfig.getCertHolderId() + "PUT")
                    .getJsonNumber("registered_at")
                    .longValue())
            .add(
                "signature",
                object.getJsonObject(clientConfig.getCertHolderId() + "PUT").getString("signature"))
            .build();
    JsonObject inGetContract =
        Json.createObjectBuilder()
            .add("contract_name", "com.scalar.client.tool.explorer.contract.GetContract")
            .add("cert_id", clientConfig.getCertHolderId())
            .add("cert_version", 1)
            .add("contract_properties", Json.createObjectBuilder().build())
            .add(
                "registered_at",
                object
                    .getJsonObject(clientConfig.getCertHolderId() + "GET")
                    .getJsonNumber("registered_at")
                    .longValue())
            .add(
                "signature",
                object.getJsonObject(clientConfig.getCertHolderId() + "GET").getString("signature"))
            .build();
    JsonObject inScanContract =
        Json.createObjectBuilder()
            .add("contract_name", "com.scalar.client.tool.explorer.contract.ScanContract")
            .add("cert_id", clientConfig.getCertHolderId())
            .add("cert_version", 1)
            .add("contract_properties", Json.createObjectBuilder().build())
            .add(
                "registered_at",
                object
                    .getJsonObject(clientConfig.getCertHolderId() + "SCAN")
                    .getJsonNumber("registered_at")
                    .longValue())
            .add(
                "signature",
                object
                    .getJsonObject(clientConfig.getCertHolderId() + "SCAN")
                    .getString("signature"))
            .build();

    return Json.createObjectBuilder()
        .add(clientConfig.getCertHolderId() + "GET", inGetContract)
        .add(clientConfig.getCertHolderId() + "PUT", inPutContract)
        .add(clientConfig.getCertHolderId() + "SCAN", inScanContract)
        .build();
  }

  @Test
  public void Test1_getEndToEnd() throws UnsupportedEncodingException {
    // Arrange
    Object[] asset = {4, "book-E", "book-D"};

    // Act
    JsonObject actual =
        (JsonObject) parse(getOutput("get", "book", "-v", "-f", propertiesFilePath));
    JsonObject expected = setAssetBase(actual, asset);

    // Assert
    assertThat(actual).isEqualTo(expected);
    assertThat(getOutput("get", "book", "-f", propertiesFilePath)).isNotNull();
    assertThat(getOutput("get", "book", "--format", "yaml", "-f", propertiesFilePath)).isNotNull();
    assertThat(getOutput("get", "book", "-v", "--format", "yaml", "-f", propertiesFilePath))
        .isNotNull();
  }

  @Test
  public void Test2_runScanEndToEnd() throws UnsupportedEncodingException {
    // Arrange
    List<Object[]> list = new ArrayList<>();
    Object[] asset1 = {4, "book-E", "book-D"};
    Object[] asset2 = {3, "book-D", "book-C"};
    Object[] asset3 = {2, "book-C", "book-B"};
    Object[] asset4 = {1, "book-B", "book-A"};
    Object[] asset5 = {0, "book-A", ""};
    list.add(asset1);
    list.add(asset2);
    list.add(asset3);
    list.add(asset4);
    list.add(asset5);

    JsonArray result = (JsonArray) parse(getOutput("scan", "book", "-v", "-f", propertiesFilePath));
    JsonArray expected = setScanExpected(result, list);
    assertThat(5).isEqualTo(result.size());
    assertThat(expected).isEqualTo(result);

    assertThat(parse(getOutput("scan", "book", "-f", propertiesFilePath))).isNotNull();
    assertThat(getOutput("scan", "book", "--format", "yaml", "-f", propertiesFilePath)).isNotNull();
    assertThat(getOutput("scan", "book", "-v", "--format", "yaml", "-f", propertiesFilePath))
        .isNotNull();
    result =
        (JsonArray)
            parse(getOutput("scan", "book", "-s", "3", "-e", "4", "-f", propertiesFilePath));
    assertThat(1).isEqualTo(result.size());
    result =
        (JsonArray)
            parse(getOutput("scan", "book", "-s", "2", "-e", "5", "-f", propertiesFilePath));
    assertThat(3).isEqualTo(result.size());
    result = (JsonArray) parse(getOutput("scan", "book", "-l", "2", "-f", propertiesFilePath));
    assertThat(2).isEqualTo(result.size());
    result = (JsonArray) parse(getOutput("scan", "book", "-a", "-f", propertiesFilePath));
    assertThat(0).isEqualTo(result.getJsonObject(0).getInt("age"));
    assertThat(1).isEqualTo(result.getJsonObject(1).getInt("age"));
    assertThat(2).isEqualTo(result.getJsonObject(2).getInt("age"));
    assertThat(3).isEqualTo(result.getJsonObject(3).getInt("age"));
    assertThat(4).isEqualTo(result.getJsonObject(4).getInt("age"));
  }

  @Test
  public void Test3_runContractsEndToEnd() throws Exception {
    // Act
    JsonStructure actual = parse(getOutput("list-contracts", "-f", propertiesFilePath));
    JsonObject expected = contractsExpected(actual);

    // Assert
    assertThat(actual).isEqualTo(expected);
  }

  @Test
  public void Test4_runValidateEndToEnd() throws Exception {
    // Assert
    assertThat("book is not tampered\n")
        .isEqualTo(getOutput("validate", "book", "-f", propertiesFilePath));
  }
}
