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

package com.scalar.client.tool.explorer;

import com.scalar.client.config.ClientConfig;
import com.scalar.client.service.ClientService;
import com.scalar.client.service.StatusCode;
import com.scalar.rpc.ContractExecutionResponse;
import com.scalar.rpc.LedgerServiceResponse;
import com.scalar.rpc.LedgerValidationResponse;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonReader;

public class Explorer {
  private final ClientService clientService;
  private final ClientConfig clientConfig;

  public Explorer(ClientService clientService, ClientConfig clientConfig) {
    this.clientService = clientService;
    this.clientConfig = clientConfig;
  }

  private ClientServiceResponse fallback(ClientServiceRequester requester) {
    ClientServiceResponse response = requester.request();
    if (response.getStatus() == StatusCode.CERTIFICATE_NOT_FOUND.get()) {
      registerCertificate();
      response = requester.request();
    }
    if (response.getStatus() == StatusCode.CONTRACT_NOT_FOUND.get()) {
      registerGetContract();
      registerScanContract();
      response = requester.request();
    }
    if (response.getStatus() != StatusCode.OK.get()) {
      throw new ExplorerException(response.getStatus() + " " + response.getMessage());
    }
    return response;
  }

  private void registerCertificate() {
    LedgerServiceResponse response = clientService.registerCertificate();
    if (response.getStatus() != StatusCode.OK.get()) {
      throw new ExplorerException(response.getStatus() + " " + response.getMessage());
    }
  }

  private void registerGetContract() {
    registerContract(
        clientConfig.getCertHolderId() + "GET",
        "GetContract.class",
        "com.scalar.client.tool.explorer.contract.GetContract");
  }

  private void registerScanContract() {
    registerContract(
        clientConfig.getCertHolderId() + "SCAN",
        "ScanContract.class",
        "com.scalar.client.tool.explorer.contract.ScanContract");
  }

  private void registerContract(String contractId, String contractClass, String contractName) {
    try {
      InputStream inputStream = Explorer.class.getClassLoader().getResourceAsStream(contractClass);
      File tmp = File.createTempFile("a_contract", ".class");
      tmp.deleteOnExit();
      Files.copy(inputStream, tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
      LedgerServiceResponse response =
          clientService.registerContract(contractId, contractName, tmp.getPath(), Optional.empty());
      if (response.getStatus() != StatusCode.OK.get()) {
        throw new ExplorerException(response.getStatus() + " " + response.getMessage());
      }
    } catch (IOException e) {
      throw new ExplorerException(e.getMessage());
    }
  }

  public void validate(String assetId) {
    ClientServiceRequester requester =
        () -> new ClientServiceResponse(clientService.validateLedger(assetId));
    fallback(requester);
  }

  public JsonObject listContracts() {
    ClientServiceRequester requester =
        () -> new ClientServiceResponse(clientService.listContracts(null));
    ClientServiceResponse response = fallback(requester);
    return string2Json(response.getMessage());
  }

  public JsonObject get(String assetId) {
    String id = clientConfig.getCertHolderId() + "GET";
    JsonObject argument = Json.createObjectBuilder().add("asset_id", assetId).build();
    return executeContract(id, argument);
  }

  public JsonArray scan(String assetId, JsonObject condition) {
    String id = clientConfig.getCertHolderId() + "SCAN";
    JsonObject argument = Json.createObjectBuilder(condition).add("asset_id", assetId).build();
    JsonObject result = executeContract(id, argument);
    return result.getJsonArray("history");
  }

  private JsonObject executeContract(String id, JsonObject argument) {
    ClientServiceRequester requester =
        () -> new ClientServiceResponse(clientService.executeContract(id, argument));
    ClientServiceResponse response = fallback(requester);
    return string2Json(response.getResult());
  }

  private JsonObject string2Json(String s) {
    JsonReader reader = Json.createReader(new StringReader(s));
    JsonObject json = reader.readObject();
    reader.close();
    return json;
  }

  @FunctionalInterface
  interface ClientServiceRequester {
    ClientServiceResponse request();
  }

  class ClientServiceResponse {
    private final int status;
    private final String message;
    private final String result;

    ClientServiceResponse(LedgerValidationResponse r) {
      status = r.getStatus();
      message = r.getMessage();
      result = "";
    }

    ClientServiceResponse(LedgerServiceResponse r) {
      status = r.getStatus();
      message = r.getMessage();
      result = "";
    }

    ClientServiceResponse(ContractExecutionResponse r) {
      status = r.getStatus();
      message = r.getMessage();
      result = r.getResult();
    }

    int getStatus() {
      return status;
    }

    String getMessage() {
      return message;
    }

    String getResult() {
      return result;
    }
  }
}
