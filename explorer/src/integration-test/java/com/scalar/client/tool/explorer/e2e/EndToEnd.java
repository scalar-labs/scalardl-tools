package com.scalar.client.tool.explorer.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.scalar.client.config.ClientConfig;
import com.scalar.client.service.ClientModule;
import com.scalar.client.service.ClientService;
import com.scalar.client.tool.explorer.command.Explorer;
import com.scalar.client.tool.explorer.e2e.contract.PutContract;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonReader;
import javax.json.JsonStructure;
import javax.json.JsonValue;
import javax.json.stream.JsonParsingException;
import javax.xml.bind.DatatypeConverter;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;
import picocli.CommandLine;

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class EndToEnd {
  private static String propertiesFile = "src/integration-test/resources/client_e2e.properties";

  @BeforeClass
  public static void setup() throws Exception {
    String contractClass = "PutContract.class";
    String contractName = "com.scalar.client.tool.explorer.e2e.contract.PutContract";
    ClientConfig clientConfig = new ClientConfig(new File(propertiesFile));
    Injector injector = Guice.createInjector(new ClientModule(clientConfig));
    ClientService clientService = injector.getInstance(ClientService.class);
    clientService.registerCertificate();
    String contractId = clientConfig.getCertHolderId() + "PUT";
    InputStream inputStream =
        new URL(PutContract.class.getResource("."), contractClass).openStream();
    File tmp = File.createTempFile("whatever", ".class");
    tmp.deleteOnExit();
    Files.copy(inputStream, tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
    clientService.registerContract(contractId, contractName, tmp.getPath(), Optional.empty());

    clientService.executeContract(contractId, put("book", "book-A"));
    clientService.executeContract(contractId, put("book", "book-B"));
    clientService.executeContract(contractId, put("book", "book-C"));
    clientService.executeContract(contractId, put("book", "book-D"));
    clientService.executeContract(contractId, put("book", "book-E"));
  }

  public String getOutput(String... args) throws UnsupportedEncodingException {
    String[] commandArgs = args.length != 0 ? args : new String[] {"-h"};
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (PrintStream ps = new PrintStream(output, true, "UTF-8")) {
      PrintStream previousOut = System.out;
      System.setOut(ps);
      CommandLine.run(new Explorer(), ps, commandArgs);
      System.setOut(previousOut);
    }
    System.out.println(output.toString());
    return output.toString();
  }

  public JsonStructure parse(String output) {
    JsonStructure result = null;
    try {
      JsonReader jsonReader = Json.createReader(new StringReader(output));
      result = jsonReader.read();
      jsonReader.close();
    } catch (JsonParsingException e) {
      result = null;
    }
    return result;
  }

  protected static String getHashHexString(String name) {
    String id = null;
    if (name != null) {
      try {
        MessageDigest md5 = MessageDigest.getInstance("MD5");
        byte[] bytes = md5.digest(name.getBytes());
        id = DatatypeConverter.printHexBinary(bytes);
      } catch (NoSuchAlgorithmException e) {
        e.printStackTrace();
      }
    }
    return id;
  }

  public static JsonObject put(String type, String asset) {
    JsonObjectBuilder builder =
        Json.createObjectBuilder()
            .add(PutContract.TYPE, type)
            .add(PutContract.ASSET, asset)
            .add(PutContract.TIMESTAMP, new Date().getTime())
            .add(PutContract.ID, getHashHexString(type + "_" + asset));
    return builder.build();
  }

  public JsonObject setAssetBase(JsonObject json, Object[] obj) {
    int age = (Integer) obj[0];
    String assetId = obj[1].toString();
    String inputId = obj[2].toString();

    JsonObject dataInAsset =
        Json.createObjectBuilder()
            .add("id", getHashHexString("book" + "_" + assetId))
            .add("name", assetId)
            .build();

    JsonObject bookInAsset =
        Json.createObjectBuilder()
            .add("age", age <= 0 ? 0 : age - 1)
            .add(
                "data",
                Json.createObjectBuilder()
                    .add("id", getHashHexString("book" + "_" + inputId))
                    .add("name", inputId)
                    .build())
            .build();
    JsonObject inputInAsset = null;
    if (inputId == "") {
      inputInAsset = Json.createObjectBuilder().build();
    } else {
      inputInAsset = Json.createObjectBuilder().add("book", bookInAsset).build();
    }
    JsonObject argumentInAsset =
        Json.createObjectBuilder()
            .add("type", "book")
            .add("asset", assetId)
            .add("timestamp", json.getJsonObject("argument").getJsonNumber("timestamp").longValue())
            .add("id", getHashHexString("book" + "_" + assetId))
            .add("nonce", json.getJsonObject("argument").getString("nonce"))
            .build();

    return Json.createObjectBuilder()
        .add("id", "book")
        .add("age", age)
        .add("data", dataInAsset)
        .add("input", inputInAsset)
        .add("contract_id", "fooPUT")
        .add("argument", argumentInAsset)
        .add("signature", json.getString("signature"))
        .add("prev_hash", json.getString("prev_hash"))
        .add("hash", json.getString("hash"))
        .build();
  }

  public JsonArray setScanExpected(JsonStructure result, List<Object[]> list) {
    int i = 0;
    JsonArrayBuilder builder = Json.createArrayBuilder();
    JsonArray array = (JsonArray) result;
    for (JsonValue obj : array) {
      JsonObject asset = setAssetBase(obj.asJsonObject(), list.get(i));
      builder.add(asset);
      i++;
    }

    return builder.build();
  }

  public JsonObject contractsExpected(JsonStructure result) {
    JsonObject object = (JsonObject) result;
    JsonObject inPutContract =
        Json.createObjectBuilder()
            .add("contract_name", "com.scalar.client.tool.explorer.e2e.contract.PutContract")
            .add("cert_id", "foo")
            .add("cert_version", 1)
            .add("contract_properties", Json.createObjectBuilder().build())
            .add(
                "registered_at",
                object.getJsonObject("fooPUT").getJsonNumber("registered_at").longValue())
            .add("signature", object.getJsonObject("fooPUT").getString("signature"))
            .build();
    JsonObject inGetContract =
        Json.createObjectBuilder()
            .add("contract_name", "com.scalar.client.tool.explorer.contract.GetContract")
            .add("cert_id", "foo")
            .add("cert_version", 1)
            .add("contract_properties", Json.createObjectBuilder().build())
            .add(
                "registered_at",
                object.getJsonObject("fooGET").getJsonNumber("registered_at").longValue())
            .add("signature", object.getJsonObject("fooGET").getString("signature"))
            .build();
    JsonObject inScanContract =
        Json.createObjectBuilder()
            .add("contract_name", "com.scalar.client.tool.explorer.contract.ScanContract")
            .add("cert_id", "foo")
            .add("cert_version", 1)
            .add("contract_properties", Json.createObjectBuilder().build())
            .add(
                "registered_at",
                object.getJsonObject("fooSCAN").getJsonNumber("registered_at").longValue())
            .add("signature", object.getJsonObject("fooSCAN").getString("signature"))
            .build();

    return Json.createObjectBuilder()
        .add("fooGET", inGetContract)
        .add("fooPUT", inPutContract)
        .add("fooSCAN", inScanContract)
        .build();
  }

  @Test
  public void T1_runGetEndToEnd() throws Exception {
    JsonStructure result = null;
    JsonObject expected = null;
    // get verbose
    result = parse(getOutput("get", "book", "-v", "-f", propertiesFile));
    Object[] asset = {4, "book-E", "book-D"};
    expected = setAssetBase((JsonObject) result, asset);
    assertThat(expected).isEqualTo(result);
    // get default
    assertThat(parse(getOutput("get", "book", "-f", propertiesFile))).isNotNull();
    // get format
    assertThat(getOutput("get", "book", "-fm", "yaml", "-f", propertiesFile)).isNotNull();
    assertThat(getOutput("get", "book", "-v", "-fm", "yaml", "-f", propertiesFile)).isNotNull();
  }

  @Test
  public void T2_runScanEndToEnd() throws Exception {
    JsonStructure result = null;
    JsonArray expected = null;
    JsonArray array = null;
    // scan
    result = parse(getOutput("scan", "book", "-v", "-f", propertiesFile));
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
    expected = setScanExpected(result, list);
    array = (JsonArray) result;
    assertThat(5).isEqualTo(array.size());
    assertThat(expected).isEqualTo(result);
    // scan default
    assertThat(parse(getOutput("scan", "book", "-f", propertiesFile))).isNotNull();
    // scan format
    assertThat(getOutput("scan", "book", "-fm", "yaml", "-f", propertiesFile)).isNotNull();
    assertThat(getOutput("scan", "book", "-v", "-fm", "yaml", "-f", propertiesFile)).isNotNull();
    // scan start, end
    array =
        (JsonArray) parse(getOutput("scan", "book", "-s", "3", "-e", "4", "-f", propertiesFile));
    assertThat(1).isEqualTo(array.size());
    array =
        (JsonArray) parse(getOutput("scan", "book", "-s", "2", "-e", "5", "-f", propertiesFile));
    assertThat(3).isEqualTo(array.size());
    array = (JsonArray) parse(getOutput("scan", "book", "-l", "2", "-f", propertiesFile));
    assertThat(2).isEqualTo(array.size());
    // scan ascending order
    array = (JsonArray) parse(getOutput("scan", "book", "-a", "-f", propertiesFile));
    assertThat(0).isEqualTo(array.getJsonObject(0).getInt("age"));
    assertThat(1).isEqualTo(array.getJsonObject(1).getInt("age"));
    assertThat(2).isEqualTo(array.getJsonObject(2).getInt("age"));
    assertThat(3).isEqualTo(array.getJsonObject(3).getInt("age"));
    assertThat(4).isEqualTo(array.getJsonObject(4).getInt("age"));
  }

  @Test
  public void T3_runContractsEndToEnd() throws Exception {
    // act
    JsonStructure result = parse(getOutput("list-contracts", "-f", propertiesFile));
    JsonObject expected = contractsExpected(result);
    // assert
    assertThat(expected).isEqualTo(result);
  }

  @Test
  public void T4_runValidateEndToEnd() throws Exception {
    // assert
    assertThat("book is not tampered\n")
        .isEqualTo(getOutput("validate", "book", "-f", propertiesFile));
  }
}
