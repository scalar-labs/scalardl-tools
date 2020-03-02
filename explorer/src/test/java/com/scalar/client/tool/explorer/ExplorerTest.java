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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.scalar.dl.client.config.ClientConfig;
import com.scalar.dl.client.exception.ClientException;
import com.scalar.dl.client.service.ClientService;
import com.scalar.dl.ledger.model.ContractExecutionResult;
import com.scalar.dl.ledger.service.StatusCode;
import java.io.StringReader;
import java.util.Optional;
import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class ExplorerTest {
  private static String HOLDER_ID = "abc";
  private static String ASSET_ID = "xyz";
  @Mock private ClientConfig clientConfig;
  @Mock private ClientService clientService;
  @Mock private ContractExecutionResult contractExecutionResultOK;
  @Mock private ClientException clientException;
  private Explorer explorer;
  private ClientException clientExceptionCertificateNotFound;
  private ClientException clientExceptionContractNotFound;

  @Rule public ExpectedException thrown = ExpectedException.none();

  @Before
  public void setUp() {
    MockitoAnnotations.initMocks(this);

    clientExceptionCertificateNotFound =
            new ClientException("Certificate not found", StatusCode.CERTIFICATE_NOT_FOUND);
    clientExceptionContractNotFound =
            new ClientException("contract not found", StatusCode.CONTRACT_NOT_FOUND);
    explorer = new Explorer(clientService, clientConfig);

    when(clientConfig.getCertHolderId()).thenReturn(HOLDER_ID);
  }

  @Test
  public void get_CertificateAndContractNotRegistered_ShouldSucceed() {
    // Arrange
    JsonObject expected = Json.createObjectBuilder().add("status", StatusCode.OK.get()).build();
    when(contractExecutionResultOK.getResult()).thenReturn(Optional.of(expected));
    when(clientService.executeContract(eq(HOLDER_ID + "GET"), any(JsonObject.class)))
        .thenReturn(contractExecutionResultOK);

    // Act
    JsonObject actual = explorer.get(ASSET_ID);

    // Assert
    verify(clientService).executeContract(eq(HOLDER_ID + "GET"), any(JsonObject.class));
    assertThat(actual).isEqualTo(expected);
  }

  @Test
  public void get_FailedToRegisterCertificate_ShouldThrowExplorerException() {
    // Arrange
    when(clientService.executeContract(eq( HOLDER_ID + "GET"), any(JsonObject.class)))
        .thenThrow(clientExceptionCertificateNotFound);
    doNothing().when(clientService).registerCertificate();

    // Act-assert
    assertThatThrownBy(() -> explorer.get(ASSET_ID))
        .isInstanceOf(ExplorerException.class)
        .hasMessage("403 Certificate not found");
  }

  @Test
  public void get_CertificateNotFound_ShouldThrowExplorerException() {
    // Arrange
    when(clientService.executeContract(eq(HOLDER_ID + "GET"), any(JsonObject.class)))
        .thenThrow(clientExceptionCertificateNotFound);

    // Act-assert
    assertThatThrownBy(() -> explorer.get(ASSET_ID))
        .isInstanceOf(ExplorerException.class)
        .hasMessage("403 Certificate not found");
  }

  @Test
  public void scan_ShouldReturnSuccess() {
    // Arrange
    JsonObject expected = Json.createReader(
            new StringReader("{\"status\":\"OK\"," +
                    "\"history\":[{\"id\":\"bar\",\"age\":0,\"data\":{}}]}"))
            .readObject();
    when(contractExecutionResultOK.getResult()).thenReturn(Optional.of(expected));
    when(clientService.executeContract(eq(HOLDER_ID + "SCAN"), any(JsonObject.class)))
        .thenReturn(contractExecutionResultOK);

    // Act
    JsonArray actual = explorer.scan(ASSET_ID, Json.createObjectBuilder().build());

    // Assert
    verify(clientService).executeContract(eq(HOLDER_ID + "SCAN"), any(JsonObject.class));
    assertThat(actual.getJsonObject(0).getString("id")).isEqualTo("bar");
    assertThat(actual.getJsonObject(0).getInt("age")).isEqualTo(0);
  }

  @Test
  public void scan_FailedResponseWhenRegisteringCertificate_ShouldThrowExplorerException() {
    // Arrange
    when(clientService.executeContract(eq(HOLDER_ID + "SCAN"), any(JsonObject.class)))
        .thenThrow(clientExceptionCertificateNotFound);
    doNothing().when(clientService).registerCertificate();

    // Act-assert
    assertThatThrownBy(() -> explorer.scan(ASSET_ID, Json.createObjectBuilder().build()))
        .isInstanceOf(ExplorerException.class)
        .hasMessage("403 Certificate not found");
  }

  @Test
  public void get_ContractNotFound_ShouldThrowExplorerException() {
    // Arrange
    when(clientService.executeContract(eq(HOLDER_ID + "GET"), any(JsonObject.class)))
            .thenThrow(clientExceptionContractNotFound);

    // Act-assert
    assertThatThrownBy(() -> explorer.get(ASSET_ID))
            .isInstanceOf(ExplorerException.class)
            .hasMessage("404 contract not found");
  }

  @Test
  public void scan_ContractNotFound_ShouldThrowsExplorerException() {
    // Arrange
    when(clientService.executeContract(eq(HOLDER_ID + "SCAN"), any(JsonObject.class)))
            .thenThrow(clientExceptionContractNotFound);

    // Act-assert
    assertThatThrownBy(() -> explorer.scan(ASSET_ID, Json.createObjectBuilder().build()))
            .isInstanceOf(ExplorerException.class)
            .hasMessage("404 contract not found");
  }

  @Test
  public void scan_CertificateNotFound_ShouldThrowExplorerException() {
    // Arrange
    when(clientService.executeContract(eq(HOLDER_ID + "SCAN"), any(JsonObject.class)))
        .thenThrow(clientExceptionCertificateNotFound);

    // Act-assert
    assertThatThrownBy(() -> explorer.scan(ASSET_ID, Json.createObjectBuilder().build()))
        .isInstanceOf(ExplorerException.class)
        .hasMessage("403 Certificate not found");
  }

  @Test
  public void validate_ErrorStatusCodeReturned_ShouldThrowExplorerException() {
    // Arrange
    when(clientException.getStatusCode()).thenReturn(StatusCode.DATABASE_ERROR);
    when(clientException.getMessage()).thenReturn("message");
    when(clientService.validateLedger(ASSET_ID)).thenThrow(clientException);

    // Act-assert
    assertThatThrownBy(() -> explorer.validate(ASSET_ID))
        .isInstanceOf(ExplorerException.class)
        .hasMessage("500 message");
  }

  @Test
  public void contracts_ErrorStatusCodeReturned_ShouldThrowExplorerException() {
    // Arrange
    when(clientException.getStatusCode()).thenReturn(StatusCode.DATABASE_ERROR);
    when(clientException.getMessage()).thenReturn("message");
    when(clientService.listContracts(null)).thenThrow(clientException);

    // Act-assert
    assertThatThrownBy(() -> explorer.listContracts())
            .isInstanceOf(ExplorerException.class)
            .hasMessage("500 message");
  }
}
