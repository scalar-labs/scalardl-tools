package com.scalar.client.tool.explorer.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scalar.ledger.asset.InternalAsset;
import com.scalar.ledger.exception.ContractContextException;
import com.scalar.ledger.ledger.Ledger;
import java.util.Base64;
import java.util.Optional;
import javax.json.Json;
import javax.json.JsonObject;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

public class GetContractTest {
  @Mock private Ledger ledger;
  private GetContract get = new GetContract();

  @Before
  public void setUp() {
    MockitoAnnotations.initMocks(this);
  }

  private InternalAsset createInternalAsset() {
    return new InternalAsset() {
      @Override
      public JsonObject input() {
        return Json.createObjectBuilder().add("input", "input").build();
      }

      @Override
      public byte[] signature() {
        return "signature".getBytes();
      }

      @Override
      public String contractId() {
        return "contract";
      }

      @Override
      public JsonObject argument() {
        return Json.createObjectBuilder().add("argument", "argument").build();
      }

      @Override
      public byte[] hash() {
        return "hash".getBytes();
      }

      @Override
      public byte[] prevHash() {
        return "prevhash".getBytes();
      }

      @Override
      public String id() {
        return "id";
      }

      @Override
      public int age() {
        return 0;
      }

      @Override
      public JsonObject data() {
        return Json.createObjectBuilder().add("data", "data").build();
      }
    };
  }

  @Test
  public void getContract_AssetIdNotGiven_ShouldThrowContractContextException() {
    // Act-assert
    assertThatThrownBy(
            () -> get.invoke(ledger, Json.createObjectBuilder().build(), Optional.empty()))
        .isInstanceOf(ContractContextException.class)
        .hasMessage("asset_id attribute is missing");
  }

  @Test
  public void getContract_AssetDoesNotExistInLedger_ShouldThrowContractContextException() {
    // Arrange
    JsonObject argument = Json.createObjectBuilder().add("asset_id", "asset").build();
    Mockito.when(ledger.get("asset")).thenReturn(Optional.empty());

    // Act-assert
    assertThatThrownBy(() -> get.invoke(ledger, argument, Optional.empty()))
        .isInstanceOf(ContractContextException.class)
        .hasMessage("asset does not exist");
  }

  @Test
  public void getContract_AssetIdIsPresent_ShouldReturnAssetJsonObject() {
    // Arrange
    Base64.Encoder encoder = Base64.getEncoder();
    InternalAsset asset = createInternalAsset();
    Mockito.when(ledger.get("asset")).thenReturn(Optional.of(asset));
    JsonObject argument = Json.createObjectBuilder().add("asset_id", "asset").build();

    // Act
    JsonObject result = get.invoke(ledger, argument, Optional.empty());

    // Assert
    assertThat(asset.id()).isEqualTo(result.getString("id"));
    assertThat(asset.age()).isEqualTo(result.getInt("age"));
    assertThat(asset.data()).isEqualTo(result.getJsonObject("data"));
    assertThat(asset.input()).isEqualTo(result.getJsonObject("input"));
    assertThat(asset.contractId()).isEqualTo(result.getString("contract_id"));
    assertThat(asset.argument()).isEqualTo(result.getJsonObject("argument"));
    assertThat(encoder.encodeToString(asset.signature())).isEqualTo(result.getString("signature"));
    assertThat(encoder.encodeToString(asset.prevHash())).isEqualTo(result.getString("prev_hash"));
    assertThat(encoder.encodeToString(asset.hash())).isEqualTo(result.getString("hash"));
  }
}
