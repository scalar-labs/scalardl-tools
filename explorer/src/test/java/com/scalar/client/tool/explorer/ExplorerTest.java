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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.scalar.client.config.ClientConfig;
import com.scalar.client.service.ClientService;
import com.scalar.client.service.StatusCode;
import com.scalar.rpc.ContractExecutionResponse;
import com.scalar.rpc.LedgerServiceResponse;
import com.scalar.rpc.LedgerValidationResponse;
import java.util.Optional;
import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

public class ExplorerTest {
  private static String HOLDER_ID = "abc";
  private static String ASSET_ID = "xyz";
  @Mock private ClientConfig clientConfig;
  @Mock private ClientService clientService;
  @Mock private ContractExecutionResponse contractExecutionResponseOK;
  @Mock private ContractExecutionResponse contractExecutionResponseCertificateNotFound;
  @Mock private ContractExecutionResponse contractExecutionResponseContractNotFound;
  @Mock private LedgerServiceResponse ledgerServiceResponseOK;
  @Mock private LedgerServiceResponse ledgerServiceResponseCertificateNotFound;
  @Mock private LedgerServiceResponse responseListContract;
  @Mock private LedgerValidationResponse responseValidation;
  private Explorer explorer;

  @Rule public ExpectedException thrown = ExpectedException.none();

  @Before
  public void setUp() {
    MockitoAnnotations.initMocks(this);

    explorer = new Explorer(clientService, clientConfig);
    when(clientConfig.getCertHolderId()).thenReturn(HOLDER_ID);

    when(clientService.registerCertificate()).thenReturn(ledgerServiceResponseOK);
    when(clientService.registerContract(
            anyString(), anyString(), anyString(), eq(Optional.empty())))
        .thenReturn(ledgerServiceResponseOK);

    when(ledgerServiceResponseOK.getStatus()).thenReturn(StatusCode.OK.get());
    when(ledgerServiceResponseCertificateNotFound.getStatus())
        .thenReturn(StatusCode.CERTIFICATE_NOT_FOUND.get());
    when(ledgerServiceResponseCertificateNotFound.getMessage()).thenReturn("certificate not found");

    when(contractExecutionResponseOK.getStatus()).thenReturn(StatusCode.OK.get());
    when(contractExecutionResponseCertificateNotFound.getStatus())
        .thenReturn(StatusCode.CERTIFICATE_NOT_FOUND.get());
    when(contractExecutionResponseCertificateNotFound.getMessage())
        .thenReturn("certificate not found");
    when(contractExecutionResponseContractNotFound.getStatus())
        .thenReturn(StatusCode.CONTRACT_NOT_FOUND.get());
    when(contractExecutionResponseContractNotFound.getMessage()).thenReturn("contract not found");
    System.out.println("test");
  }

  @Test
  public void get_CertificateAndContractNotRegistered_ShouldSucceed() {
    // Arrange
    when(contractExecutionResponseOK.getResult()).thenReturn("{ \"id\": \"bar\" }");
    when(clientService.executeContract(eq(HOLDER_ID + "GET"), any(JsonObject.class)))
        .thenReturn(
            contractExecutionResponseCertificateNotFound,
            contractExecutionResponseContractNotFound,
            contractExecutionResponseOK);

    // Act
    JsonObject result = explorer.get(ASSET_ID);

    // Assert
    verify(clientService, Mockito.times(3))
        .executeContract(eq(HOLDER_ID + "GET"), any(JsonObject.class));
    assertThat(result.getString("id")).isEqualTo("bar");
  }

  @Test
  public void get_FailedToRegisterCertificate_ShouldThrowExplorerException() {
    // Arrange
    when(clientService.executeContract(eq(HOLDER_ID + "GET"), any(JsonObject.class)))
        .thenReturn(contractExecutionResponseCertificateNotFound);
    when(clientService.registerCertificate()).thenReturn(ledgerServiceResponseCertificateNotFound);

    // Act-assert
    assertThatThrownBy(() -> explorer.get(ASSET_ID))
        .isInstanceOf(ExplorerException.class)
        .hasMessage("403 certificate not found");
  }

  @Test
  public void get_CertificateNotFoundTwice_ShouldThrowExplorerException() {
    // Arrange
    when(clientService.executeContract(eq(HOLDER_ID + "GET"), any(JsonObject.class)))
        .thenReturn(
            contractExecutionResponseCertificateNotFound,
            contractExecutionResponseCertificateNotFound);

    // Act-assert
    assertThatThrownBy(() -> explorer.get(ASSET_ID))
        .isInstanceOf(ExplorerException.class)
        .hasMessage("403 certificate not found");
  }

  @Test
  public void get_ContractNotFoundTwice_ShouldThrowExplorerException() {
    // Arrange
    when(clientService.executeContract(eq(HOLDER_ID + "GET"), any(JsonObject.class)))
        .thenReturn(
            contractExecutionResponseContractNotFound, contractExecutionResponseContractNotFound);

    // Act-assert
    assertThatThrownBy(() -> explorer.get(ASSET_ID))
        .isInstanceOf(ExplorerException.class)
        .hasMessage("404 contract not found");
  }

  @Test
  public void scan_ThrowExceptionOnlyOneTime_ShouldReturnSuccess() {
    // Arrange
    when(contractExecutionResponseOK.getResult())
        .thenReturn(
            "{\"result\":\"success\",\"history\":[{\"id\":\"bar\",\"age\":0,\"data\":{}}]}");
    when(clientService.executeContract(eq(HOLDER_ID + "SCAN"), any(JsonObject.class)))
        .thenReturn(
            contractExecutionResponseCertificateNotFound,
            contractExecutionResponseContractNotFound,
            contractExecutionResponseOK);

    // Act
    JsonArray result = explorer.scan(ASSET_ID, Json.createObjectBuilder().build());

    // Assert
    verify(clientService, Mockito.times(3))
        .executeContract(eq(HOLDER_ID + "SCAN"), any(JsonObject.class));
    assertThat(result.getJsonObject(0).getString("id")).isEqualTo("bar");
    assertThat(result.getJsonObject(0).getInt("age")).isEqualTo(0);
  }

  @Test
  public void scan_FailedResponseWhenRegisteringCertificate_ShouldThrowExplorerException() {
    // Arrange
    when(clientService.executeContract(eq(HOLDER_ID + "SCAN"), any(JsonObject.class)))
        .thenReturn(contractExecutionResponseCertificateNotFound);
    when(clientService.registerCertificate()).thenReturn(ledgerServiceResponseCertificateNotFound);

    // Act-assert
    assertThatThrownBy(() -> explorer.scan(ASSET_ID, Json.createObjectBuilder().build()))
        .isInstanceOf(ExplorerException.class)
        .hasMessage("403 certificate not found");
  }

  @Test
  public void scan_CertificateNotFoundTwice_ShouldThrowExplorerException() {
    // Arrange
    when(clientService.executeContract(eq(HOLDER_ID + "SCAN"), any(JsonObject.class)))
        .thenReturn(
            contractExecutionResponseCertificateNotFound,
            contractExecutionResponseCertificateNotFound);

    // Act-assert
    assertThatThrownBy(() -> explorer.scan(ASSET_ID, Json.createObjectBuilder().build()))
        .isInstanceOf(ExplorerException.class)
        .hasMessage("403 certificate not found");
  }

  @Test
  public void scan_ContractNotFoundTwice_ShouldThrowsExplorerException() {
    // Arrange
    when(clientService.executeContract(eq(HOLDER_ID + "SCAN"), any(JsonObject.class)))
        .thenReturn(
            contractExecutionResponseContractNotFound, contractExecutionResponseContractNotFound);

    // Act-assert
    assertThatThrownBy(() -> explorer.scan(ASSET_ID, Json.createObjectBuilder().build()))
        .isInstanceOf(ExplorerException.class)
        .hasMessage("404 contract not found");
  }

  @Test
  public void validate_ErrorStatusCodeReturned_ShouldThrowExplorerException() {
    // Arrange
    when(responseValidation.getStatus()).thenReturn(StatusCode.DATABASE_ERROR.get());
    when(responseValidation.getMessage()).thenReturn("message");
    when(clientService.validateLedger(ASSET_ID)).thenReturn(responseValidation);

    // Act-assert
    assertThatThrownBy(() -> explorer.validate(ASSET_ID))
        .isInstanceOf(ExplorerException.class)
        .hasMessage("500 message");
  }

  @Test
  public void contracts_ErrorStatusCodeReturned_ShouldThrowExplorerException() {
    // Arrange
    when(responseListContract.getStatus()).thenReturn(StatusCode.DATABASE_ERROR.get());
    when(responseListContract.getMessage()).thenReturn("message");
    when(clientService.listContracts(null)).thenReturn(responseListContract);

    // Act-assert
    assertThatThrownBy(() -> explorer.listContracts())
        .isInstanceOf(ExplorerException.class)
        .hasMessage("500 message");
  }
}
