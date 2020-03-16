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

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.scalar.dl.client.config.ClientConfig;
import com.scalar.dl.client.exception.ClientException;
import com.scalar.dl.client.service.ClientService;
import com.scalar.dl.ledger.model.ContractExecutionResult;
import com.scalar.dl.ledger.service.StatusCode;
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
  public void get_ProperArgumentGiven_ShouldSucceed() {
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
  public void get_FailedToRegisterCertificate_ShouldThrowClientException() {
    // Arrange
    when(clientService.executeContract(eq( HOLDER_ID + "GET"), any(JsonObject.class)))
        .thenThrow(clientExceptionCertificateNotFound);
    doNothing().when(clientService).registerCertificate();

    // Act
    Throwable thrown = catchThrowable(() -> { explorer.get(ASSET_ID); });

    // Assert
    assertThat(thrown).isInstanceOf(ClientException.class);
    ClientException clientException = (ClientException) thrown;
    assertThat(clientException.getStatusCode()).isEqualTo(StatusCode.CERTIFICATE_NOT_FOUND);
  }

  @Test
  public void get_CertificateNotFound_ShouldThrowClientException() {
    // Arrange
    when(clientService.executeContract(eq(HOLDER_ID + "GET"), any(JsonObject.class)))
        .thenThrow(clientExceptionCertificateNotFound);

    // Act
    Throwable thrown = catchThrowable(() -> { explorer.get(ASSET_ID); });

    // Assert
    assertThat(thrown).isInstanceOf(ClientException.class);
    ClientException clientException = (ClientException) thrown;
    assertThat(clientException.getStatusCode()).isEqualTo(StatusCode.CERTIFICATE_NOT_FOUND);
  }

  @Test
  public void scan_ProperArgumentGiven_ShouldReturnSuccess() {
    // Arrange
    JsonObject expected = Json.createObjectBuilder()
            .add("status", StatusCode.OK.toString())
            .add("history", Json.createArrayBuilder()
                    .add(Json.createObjectBuilder()
                            .add("id", "bar")
                            .add("age", 0)
                            .add("data", Json.createObjectBuilder()))
                    .build())
            .build();
    when(contractExecutionResultOK.getResult()).thenReturn(Optional.of(expected));
    when(clientService.executeContract(eq(HOLDER_ID + "SCAN"), any(JsonObject.class)))
        .thenReturn(contractExecutionResultOK);

    // Act
    JsonArray actual = explorer.scan(ASSET_ID, Json.createObjectBuilder().build());

    // Assert
    verify(clientService).executeContract(eq(HOLDER_ID + "SCAN"), any(JsonObject.class));
    assertThat(actual).isEqualTo(expected.getJsonArray("history"));
  }

  @Test //(expected = ClientException.class)
  public void scan_FailedResponseWhenRegisteringCertificate_ShouldThrowClientException() {
    // Arrange
    when(clientService.executeContract(eq(HOLDER_ID + "SCAN"), any(JsonObject.class)))
        .thenThrow(clientExceptionCertificateNotFound);
    doNothing().when(clientService).registerCertificate();

    // Act
    Throwable thrown = catchThrowable(() -> { explorer.scan(ASSET_ID, Json.createObjectBuilder().build()); });

    // Assert
    assertThat(thrown).isInstanceOf(ClientException.class);
    ClientException clientException = (ClientException) thrown;
    assertThat(clientException.getStatusCode()).isEqualTo(StatusCode.CERTIFICATE_NOT_FOUND);
  }

  @Test
  public void get_ContractNotFound_ShouldThrowClientException() {
    // Arrange
    when(clientService.executeContract(eq(HOLDER_ID + "GET"), any(JsonObject.class)))
            .thenThrow(clientExceptionContractNotFound);

    // Act
    Throwable thrown = catchThrowable(() -> { explorer.get(ASSET_ID); });

    // Assert
    assertThat(thrown).isInstanceOf(ClientException.class);
    ClientException clientException = (ClientException) thrown;
    assertThat(clientException.getStatusCode()).isEqualTo(StatusCode.CONTRACT_NOT_FOUND);
  }

  @Test
  public void scan_ContractNotFound_ShouldThrowsClientException() {
    // Arrange
    when(clientService.executeContract(eq(HOLDER_ID + "SCAN"), any(JsonObject.class)))
            .thenThrow(clientExceptionContractNotFound);

    // Act
    Throwable thrown = catchThrowable(() -> { explorer.scan(ASSET_ID, Json.createObjectBuilder().build()); });

    // Assert
    assertThat(thrown).isInstanceOf(ClientException.class);
    ClientException clientException = (ClientException) thrown;
    assertThat(clientException.getStatusCode()).isEqualTo(StatusCode.CONTRACT_NOT_FOUND);
  }

  @Test
  public void scan_CertificateNotFound_ShouldThrowClientException() {
    // Arrange
    when(clientService.executeContract(eq(HOLDER_ID + "SCAN"), any(JsonObject.class)))
        .thenThrow(clientExceptionCertificateNotFound);

    // Act
    Throwable thrown = catchThrowable(() -> { explorer.scan(ASSET_ID, Json.createObjectBuilder().build()); });

    // Assert
    assertThat(thrown).isInstanceOf(ClientException.class);
    ClientException clientException = (ClientException) thrown;
    assertThat(clientException.getStatusCode()).isEqualTo(StatusCode.CERTIFICATE_NOT_FOUND);
  }

  @Test
  public void validate_ErrorStatusCodeReturned_ShouldThrowClientException() {
    // Arrange
    when(clientException.getStatusCode()).thenReturn(StatusCode.DATABASE_ERROR);
    when(clientException.getMessage()).thenReturn("message");
    when(clientService.validateLedger(ASSET_ID)).thenThrow(clientException);

    // Act
    Throwable thrown = catchThrowable(() -> { explorer.validate(ASSET_ID); });

    // Assert
    assertThat(thrown).isInstanceOf(ClientException.class);
    ClientException clientException = (ClientException) thrown;
    assertThat(clientException.getStatusCode()).isEqualTo(StatusCode.DATABASE_ERROR);
  }

  @Test
  public void contracts_ErrorStatusCodeReturned_ShouldThrowClientException() {
    // Arrange
    when(clientException.getStatusCode()).thenReturn(StatusCode.DATABASE_ERROR);
    when(clientException.getMessage()).thenReturn("message");
    when(clientService.listContracts((String) null)).thenThrow(clientException);

    // Act
    Throwable thrown = catchThrowable(() -> { explorer.listContracts(); });

    // Assert
    assertThat(thrown).isInstanceOf(ClientException.class);
    ClientException clientException = (ClientException) thrown;
    assertThat(clientException.getStatusCode()).isEqualTo(StatusCode.DATABASE_ERROR);
  }
}
