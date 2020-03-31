package com.scalar.client.tool.emulator;

import com.scalar.dl.ledger.contract.Contract;
import com.scalar.dl.ledger.contract.ContractEntry;
import com.scalar.dl.ledger.crypto.CertificateEntry;
import com.scalar.dl.ledger.database.ContractRegistry;
import com.scalar.dl.ledger.database.Ledger;
import com.scalar.dl.ledger.exception.RegistryIOException;
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
import org.apache.commons.lang3.reflect.FieldUtils;

public class ContractManagerEmulator {
  public static final CertificateEntry.Key defaultCertificateKey =
      new CertificateEntry.Key("default_holder_id", 1);
  private final ContractRegistry registry;
  private final Map<String, Contract> cache;
  public ContractManagerEmulator that;
  private CertificateEntry.Key emulatedCertificateKey;

  public ContractManagerEmulator(ContractRegistry registry) {
    this.registry = registry;
    this.cache = new HashMap<String, Contract>();
    this.emulatedCertificateKey = defaultCertificateKey;
    that = this;
  }

  public void register(com.scalar.dl.ledger.contract.ContractEntry entry) {
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

  public ContractEntry get(ContractEntry.Key key) {
    return registry.lookup(key);
  }

  public Contract getInstance(String id) {
    Contract contract = cache.get(id);
    if (contract == null) {
      try {
        ContractEntry.Key key =
            new ContractEntry.Key(id, new CertificateEntry.Key("emulator_user", 0));
        ContractEntry entry = registry.lookup(key);
        contract = emulateContract(entry);
        cache.put(id, contract);
      } catch (Exception e) {
        e.printStackTrace();
      }
    }

    emulateIsRoot(contract, true);
    emulateCertificateKey(contract);
    return contract;
  }

  public List<ContractEntry> scan() {
    return registry.scan("emulator_user");
  }

  public void setEmulatedCertificateKey(CertificateEntry.Key emulatedCertificateKey) {
    this.emulatedCertificateKey = emulatedCertificateKey;
  }

  /** Thes method is used to delegate contract's invoke. */
  protected JsonObject emulatedInvoke(String id, Ledger ledger, JsonObject argument) {
    try {
      Field field = this.getClass().getField("that");
      Object o = field.get(this);
      Method m = o.getClass().getDeclaredMethod("getInstance", String.class);
      Contract contract = (Contract) m.invoke(o, id);

      // Set isRoot to `false`
      FieldUtils.writeField(contract, "isRoot", false, true);
      return contract.invoke(ledger, argument, Optional.empty());
    } catch (Exception e) {
      e.printStackTrace();
    }
    return Json.createObjectBuilder().build();
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

  private Contract emulateContract(ContractEntry entry) throws Exception {
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

    for (CtMethod method : contractClass.getMethods()) {
      // Delegate `Contract.invoke(id, ledger, argument)`
      if (method
          .getLongName()
          .equals(
              "com.scalar.dl.ledger.contract.Contract.invoke(java.lang.String,com.scalar.dl.ledger.database.Ledger,javax.json.JsonObject)")) {
        CtMethod m = CtNewMethod.delegator(method, contractClass);
        CtMethod invoke = emulatorClass.getDeclaredMethod("emulatedInvoke");
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

  /**
   * Emulate the certificate holderId and version for the given Contract instance
   *
   * @param contract the contract in which the certificate will be emulated
   */
  private void emulateCertificateKey(Contract contract) {
    try {
      FieldUtils.writeField(contract, "certificateKey", emulatedCertificateKey, true);
    } catch (IllegalAccessException e) {
      e.printStackTrace();
    }
  }

  private void emulateIsRoot(Contract contract, boolean isRoot) {
    try {
      FieldUtils.writeField(contract, "isRoot", isRoot, true);
    } catch (IllegalAccessException e) {
      e.printStackTrace();
    }
  }
}
