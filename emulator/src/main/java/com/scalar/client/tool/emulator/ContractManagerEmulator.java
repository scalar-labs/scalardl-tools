package com.scalar.client.tool.emulator;

import com.scalar.ledger.contract.Contract;
import com.scalar.ledger.contract.ContractEntry;
import com.scalar.ledger.database.ContractRegistry;
import com.scalar.ledger.exception.RegistryIOException;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.json.JsonObject;

public class ContractManagerEmulator extends ClassLoader {

  private final ContractRegistry registry;
  private final Map<String, Contract> cache;

  public ContractManagerEmulator(ContractRegistry registry) {
    this.registry = registry;
    this.cache = new HashMap<String, Contract>();
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
    Contract contract = cache.get(id);
    if (contract == null) {
      try {
        ContractEntry entry = registry.lookup(id);
        contract =
            (Contract)
                defineClass(
                        entry.getBinaryName(), entry.getByteCode(), 0, entry.getByteCode().length)
                    .getConstructor(new Class[0])
                    .newInstance(new Object[0]);
        cache.put(id, contract);
      } catch (Exception e) {
      }
    }

    return contract;
  }

  public List<ContractEntry> scan() {
    return registry.scan("emulator_user");
  }
}
