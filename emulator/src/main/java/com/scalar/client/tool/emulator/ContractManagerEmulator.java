package com.scalar.client.tool.emulator;

import com.scalar.ledger.contract.Contract;
import com.scalar.ledger.contract.ContractEntry;
import com.scalar.ledger.database.ContractRegistry;
import com.scalar.ledger.exception.RegistryIOException;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import javax.json.JsonObject;

public class ContractManagerEmulator extends ClassLoader {

  private final ContractRegistry registry;

  public ContractManagerEmulator(ContractRegistry registry) {
    this.registry = registry;
  }

  public void register(com.scalar.ledger.contract.ContractEntry entry) {
    registry.bind(entry);
  }

  public void register(String id, String name, File file, JsonObject properties) {
    try {
      byte[] contract = Files.readAllBytes(file.toPath());
      register(toContractEntry(id, name, contract, properties));
    } catch (IOException e) {
      throw new RegistryIOException("could not register contract " + id);
    }
  }

  private ContractEntry toContractEntry(
      String id, String name, byte[] contract, JsonObject properties) {
    return new ContractEntry(
        id,
        name,
        "holder_id",
        1,
        contract,
        properties,
        System.currentTimeMillis(),
        "signature".getBytes());
  }

  public ContractEntry get(ContractEntry.Key key) {
    return registry.lookup(key.getId());
  }

  public com.scalar.ledger.contract.Contract getInstance(java.lang.String id) {
    ContractEntry entry = registry.lookup(id);
    try {
      return (Contract)
          defineClass(entry.getBinaryName(), entry.getByteCode(), 0, entry.getByteCode().length)
              .getConstructor(new Class[0])
              .newInstance(new Object[0]);
    } catch (Exception e) {
      return null;
    }
  }

  public List<ContractEntry> scan() {
    return registry.scan("emulator_user");
  }
}
