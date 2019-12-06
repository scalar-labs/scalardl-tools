package com.scalar.client.tool.emulator;

import com.scalar.ledger.contract.Contract;
import com.scalar.ledger.contract.ContractEntry;
import com.scalar.ledger.database.ContractRegistry;
import com.scalar.ledger.exception.RegistryIOException;
import com.scalar.ledger.ledger.Ledger;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javassist.ByteArrayClassPath;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtField;
import javassist.CtMethod;
import javassist.CtNewMethod;
import javassist.Modifier;
import javax.json.Json;
import javax.json.JsonObject;

public class ContractManagerEmulator {

  private final ContractRegistry registry;
  private final Map<String, Contract> cache;
  public ContractManagerEmulator that;

  public ContractManagerEmulator(ContractRegistry registry) {
    this.registry = registry;
    this.cache = new HashMap<String, Contract>();
    removeContractClassInvokeMethodModifier();
    that = this;
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

  /**
   * invoke(id, ledger, argument) in Contract class has modifier `final`. To override it for
   * registered contracts, we need to reset the modifier.
   */
  private void removeContractClassInvokeMethodModifier() {
    ClassPool pool = ClassPool.getDefault();
    try {
      CtClass clazz = pool.get("com.scalar.ledger.contract.Contract");
      for (CtMethod method : clazz.getMethods()) {
        if (method
            .getLongName()
            .equals(
                "com.scalar.ledger.contract.Contract.invoke(java.lang.String,com.scalar.ledger.ledger.Ledger,javax.json.JsonObject)")) {
          method.setModifiers(Modifier.PUBLIC);
          break;
        }
      }
      clazz.toClass();
      clazz.defrost();
    } catch (Exception e) {
      e.printStackTrace();
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

  /** Thes method is used to delegate contract's invoke. */
  protected JsonObject delegatedInvoke(String id, Ledger ledger, JsonObject argument) {
    try {
      Field field = this.getClass().getField("that");
      Object o = field.get(this);
      Method m = o.getClass().getDeclaredMethod("getInstance", String.class);
      Contract contract = (Contract) m.invoke(o, id);
      return contract.invoke(ledger, argument, Optional.empty());
    } catch (Exception e) {
      e.printStackTrace();
    }
    return Json.createObjectBuilder().build();
  }

  private Contract hackContract(ContractEntry entry) throws Exception {
    ClassPool pool = ClassPool.getDefault();
    pool.insertClassPath(new ByteArrayClassPath(entry.getBinaryName(), entry.getByteCode()));
    CtClass contractClass = pool.get(entry.getBinaryName());
    CtClass emulatorClass = pool.get("com.scalar.client.tool.emulator.ContractManagerEmulator");

    // Inject a field `that` pointing to ContractManagerEmulator
    CtField that = new CtField(emulatorClass, "that", contractClass);
    that.setModifiers(Modifier.PUBLIC);
    CtMethod setter = CtNewMethod.setter("setHiddenManager", that);
    contractClass.addMethod(setter);
    contractClass.addField(that);

    // Delegate `Contract.invoke(id, ledger, argument)`
    for (CtMethod method : contractClass.getMethods()) {
      if (method
          .getLongName()
          .equals(
              "com.scalar.ledger.contract.Contract.invoke(java.lang.String,com.scalar.ledger.ledger.Ledger,javax.json.JsonObject)")) {
        CtMethod m = CtNewMethod.delegator(method, contractClass);
        CtMethod invoke = emulatorClass.getDeclaredMethod("delegatedInvoke");
        m.setBody(invoke, null);
        m.setModifiers(Modifier.PRIVATE);
        contractClass.addMethod(m);
      }
    }

    // Set `that` to this ContractManagerEmulator
    Contract contract = (Contract) contractClass.toClass().newInstance();
    Method m = contract.getClass().getMethod("setHiddenManager", this.getClass());
    m.invoke(contract, this);
    return contract;
  }

  public Contract getInstance(String id) {
    Contract contract = cache.get(id);
    if (contract == null) {
      try {
        ContractEntry entry = registry.lookup(id);
        contract = hackContract(entry);
        cache.put(id, contract);
      } catch (Exception e) {
        e.printStackTrace();
      }
    }
    return contract;
  }

  public List<ContractEntry> scan() {
    return registry.scan("emulator_user");
  }
}
