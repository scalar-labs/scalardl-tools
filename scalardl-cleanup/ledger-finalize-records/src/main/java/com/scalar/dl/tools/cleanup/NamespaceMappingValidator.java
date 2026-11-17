package com.scalar.dl.tools.cleanup;

import com.google.common.collect.ImmutableMap;
import com.scalar.db.api.DistributedStorageAdmin;
import com.scalar.db.api.TableMetadata;
import com.scalar.db.exception.storage.ExecutionException;
import com.scalar.db.storage.multistorage.MultiStorageAdmin;
import com.scalar.db.storage.multistorage.MultiStorageConfig;
import com.scalar.db.transaction.consensuscommit.ConsensusCommitUtils;
import com.scalar.dl.tools.common.ScalarDlCleanupError;
import com.scalar.dl.tools.common.ScalarDlCleanupException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Validates the namespace mapping of a multi-storage configuration against the storages.
 *
 * <p>{@code finalize-ledger} finds the tables outside the default storage only through the
 * namespace mapping, so a wrong mapping silently drops those tables from the finalization, and
 * {@code cleanup-coordinator} then makes their non-terminal records unrecoverable. This validator
 * catches the following cases before any table is processed:
 *
 * <ul>
 *   <li>A mapped namespace is not in the metadata of the storage it is mapped to, for example,
 *       because of a misspelled namespace, a leftover placeholder, or a wrong metadata schema of a
 *       JDBC storage.
 *   <li>A storage has transactional tables in a namespace that resolves to another storage: a
 *       namespace in a storage other than the default storage that is not mapped, or a namespace
 *       that is mapped to another storage than the one that has its tables.
 * </ul>
 *
 * <p>It asks each underlying storage directly because {@link MultiStorageAdmin#getNamespaceNames()}
 * returns only the namespaces that the mapping already reaches.
 */
final class NamespaceMappingValidator {

  private final Map<String, DistributedStorageAdmin> storageAdmins;
  private final Map<String, String> namespaceStorageMap;
  private final String defaultStorageName;

  /**
   * Creates a validator.
   *
   * @param storageAdmins the admins of the underlying storages keyed by storage name
   * @param namespaceStorageMap the namespace mapping, from a namespace to a storage name
   * @param defaultStorageName the name of the default storage
   */
  NamespaceMappingValidator(
      Map<String, DistributedStorageAdmin> storageAdmins,
      Map<String, String> namespaceStorageMap,
      String defaultStorageName) {
    this.storageAdmins = ImmutableMap.copyOf(storageAdmins);
    this.namespaceStorageMap = ImmutableMap.copyOf(namespaceStorageMap);
    this.defaultStorageName = defaultStorageName;
  }

  /**
   * Creates a validator from a multi-storage admin and its configuration. The validator uses the
   * underlying admins of the given admin and does not close them.
   */
  static NamespaceMappingValidator create(MultiStorageAdmin admin, MultiStorageConfig config) {
    return new NamespaceMappingValidator(
        admin.getNameAdminMap(), config.getNamespaceStorageMap(), config.getDefaultStorage());
  }

  /**
   * Throws if the namespace mapping does not match the storages.
   *
   * @throws ScalarDlCleanupException if a mapped namespace is not in the metadata of its storage,
   *     or if a storage has transactional tables in a namespace that resolves to another storage
   * @throws ExecutionException if a storage operation fails
   */
  void validate() throws ExecutionException {
    // The namespaces that the metadata of each storage has. The table discovery of finalize-ledger
    // relies on the same metadata, so the validation must not use another source, such as the
    // physical schemas of a JDBC database.
    Map<String, Set<String>> storageNamespaces = new HashMap<>();
    for (Map.Entry<String, DistributedStorageAdmin> entry : storageAdmins.entrySet()) {
      storageNamespaces.put(entry.getKey(), entry.getValue().getNamespaceNames());
    }
    throwIfMappedNamespaceNotFound(storageNamespaces);
    throwIfUnreachableNamespacesFound(storageNamespaces);
  }

  private void throwIfMappedNamespaceNotFound(Map<String, Set<String>> storageNamespaces) {
    for (Map.Entry<String, String> entry : namespaceStorageMap.entrySet()) {
      String namespace = entry.getKey();
      String storageName = entry.getValue();
      Set<String> namespaces = storageNamespaces.get(storageName);
      if (namespaces == null) {
        throw new IllegalStateException("No admin found for the storage: " + storageName);
      }
      if (!namespaces.contains(namespace)) {
        throw new ScalarDlCleanupException(
            ScalarDlCleanupError.MULTI_STORAGE_MAPPED_NAMESPACE_NOT_FOUND, namespace, storageName);
      }
    }
  }

  /**
   * Throws if a storage has transactional tables in a namespace that resolves to another storage.
   * This covers a namespace in a non-default storage that is not mapped, and a namespace in any
   * storage, including the default one, that is mapped to another storage.
   */
  private void throwIfUnreachableNamespacesFound(Map<String, Set<String>> storageNamespaces)
      throws ExecutionException {
    for (Map.Entry<String, DistributedStorageAdmin> entry : storageAdmins.entrySet()) {
      String storageName = entry.getKey();
      DistributedStorageAdmin storageAdmin = entry.getValue();
      Set<String> unreachableNamespaces = new TreeSet<>();
      for (String namespace : storageNamespaces.get(storageName)) {
        if (storageName.equals(namespaceStorageMap.getOrDefault(namespace, defaultStorageName))) {
          continue;
        }
        if (hasTransactionTable(storageAdmin, namespace)) {
          unreachableNamespaces.add(namespace);
        }
      }
      if (!unreachableNamespaces.isEmpty()) {
        throw new ScalarDlCleanupException(
            ScalarDlCleanupError.MULTI_STORAGE_UNREACHABLE_NAMESPACES_FOUND,
            storageName,
            unreachableNamespaces);
      }
    }
  }

  private static boolean hasTransactionTable(DistributedStorageAdmin storageAdmin, String namespace)
      throws ExecutionException {
    for (String table : storageAdmin.getNamespaceTableNames(namespace)) {
      TableMetadata metadata = storageAdmin.getTableMetadata(namespace, table);
      if (metadata != null && ConsensusCommitUtils.isTransactionTableMetadata(metadata)) {
        return true;
      }
    }
    return false;
  }
}
