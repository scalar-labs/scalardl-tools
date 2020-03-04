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

import com.scalar.dl.client.config.ClientConfig;
import com.scalar.dl.client.exception.ClientException;
import com.scalar.dl.client.service.ClientService;
import com.scalar.dl.ledger.model.ContractExecutionResult;
import com.scalar.dl.ledger.service.StatusCode;
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

  private void fallback(ClientException clientException) {
    if (clientException.getStatusCode().equals(StatusCode.CERTIFICATE_NOT_FOUND)) {
      registerCertificate();
    }
    if (clientException.getStatusCode().equals(StatusCode.CONTRACT_NOT_FOUND)) {
      registerGetContract();
      registerScanContract();
    }
    if (!(clientException.getStatusCode().equals(StatusCode.OK))) {
      throw clientException;
    }
  }

  private void registerCertificate() {
    try {
      clientService.registerCertificate();
    } catch (ClientException clientException) {
      throw clientException;
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
      try {
        clientService.registerContract(contractId, contractName, tmp.getPath(), Optional.empty());
      } catch (ClientException clientException) {
        throw clientException;
      }
    } catch (IOException e) {
      throw new ExplorerException("Contract not found", StatusCode.CONTRACT_NOT_FOUND);
    }
  }

  public void validate(String assetId) {
    try {
      clientService.validateLedger(assetId);
    } catch (ClientException ex) {
      fallback(ex);
    }
  }

  public JsonObject listContracts() {
    try {
      return clientService.listContracts(null);
    } catch (ClientException ex) {
      fallback(ex);
      return string2Json(ex.getMessage());
    }
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
    try {
      ContractExecutionResult result = clientService.executeContract(id, argument);

      return result.getResult().get();
    } catch (ClientException ex) {
      fallback(ex);
      return string2Json(ex.getMessage());
    }
  }

  private JsonObject string2Json(String s) {
    JsonReader reader = Json.createReader(new StringReader(s));
    JsonObject json = reader.readObject();
    reader.close();
    return json;
  }
}
