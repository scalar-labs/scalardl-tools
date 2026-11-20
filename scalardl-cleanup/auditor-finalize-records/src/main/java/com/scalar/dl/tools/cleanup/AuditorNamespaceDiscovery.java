package com.scalar.dl.tools.cleanup;

import com.scalar.db.api.DistributedStorage;
import com.scalar.db.api.DistributedStorageAdmin;
import com.scalar.db.api.Result;
import com.scalar.db.api.Scan;
import com.scalar.db.api.Scanner;
import com.scalar.db.io.Key;
import com.scalar.dl.tools.common.AuditorInternalValues;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Discovers the logical namespaces whose {@code asset_lock} tables hold the locks to finalize. The
 * physical namespace of a logical namespace is {@link AuditorInternalValues#resolveNamespace}.
 */
final class AuditorNamespaceDiscovery {

  private static final Logger logger = LoggerFactory.getLogger(AuditorNamespaceDiscovery.class);

  private final DistributedStorageAdmin admin;
  private final DistributedStorage storage;
  private final String baseNamespace;

  AuditorNamespaceDiscovery(
      DistributedStorageAdmin admin, DistributedStorage storage, String baseNamespace) {
    this.admin = admin;
    this.storage = storage;
    this.baseNamespace = baseNamespace;
  }

  /**
   * Discovers the logical namespaces to sweep: the implicit {@code default} namespace plus every
   * namespace registered in the {@code <base>.namespace} registry table, or only the default
   * namespace if that table does not exist.
   */
  List<String> discoverLogicalNamespaces() throws Exception {
    Set<String> namespaces = new LinkedHashSet<>();
    namespaces.add(AuditorInternalValues.DEFAULT_LOGICAL_NAMESPACE);

    if (admin.tableExists(baseNamespace, AuditorInternalValues.NAMESPACE_TABLE_NAME)) {
      logger.info("Namespace registry table found; scanning for registered namespaces.");
      Scan scan =
          Scan.newBuilder()
              .namespace(baseNamespace)
              .table(AuditorInternalValues.NAMESPACE_TABLE_NAME)
              .partitionKey(
                  Key.ofInt(
                      AuditorInternalValues.NAMESPACE_TABLE_PARTITION_ID_COLUMN_NAME,
                      AuditorInternalValues.NAMESPACE_TABLE_DEFAULT_PARTITION_ID))
              .build();
      try (Scanner scanner = storage.scan(scan)) {
        for (Result result : scanner) {
          String registered =
              result.getText(AuditorInternalValues.NAMESPACE_TABLE_NAME_COLUMN_NAME);
          if (namespaces.add(registered)) {
            logger.info("Discovered registered namespace: {}", registered);
          }
        }
      }
    } else {
      logger.info("Namespace registry table not found; sweeping the default namespace only.");
    }

    return new ArrayList<>(namespaces);
  }
}
