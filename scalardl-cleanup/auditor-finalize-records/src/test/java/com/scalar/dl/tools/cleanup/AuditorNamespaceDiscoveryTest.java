package com.scalar.dl.tools.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.scalar.db.api.DistributedStorage;
import com.scalar.db.api.DistributedStorageAdmin;
import com.scalar.db.api.Result;
import com.scalar.db.api.Scan;
import com.scalar.db.api.Scanner;
import com.scalar.dl.tools.common.AuditorInternalValues;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AuditorNamespaceDiscoveryTest {

  private static final String BASE_NAMESPACE = "auditor";

  private DistributedStorageAdmin admin;
  private DistributedStorage storage;
  private AuditorNamespaceDiscovery discovery;

  @BeforeEach
  void setUp() {
    admin = mock(DistributedStorageAdmin.class);
    storage = mock(DistributedStorage.class);
    discovery = new AuditorNamespaceDiscovery(admin, storage, BASE_NAMESPACE);
  }

  private Result createMockNamespaceResult(String name) {
    Result result = mock(Result.class);
    when(result.getText(AuditorInternalValues.NAMESPACE_TABLE_NAME_COLUMN_NAME)).thenReturn(name);
    return result;
  }

  @Test
  void discoverLogicalNamespaces_registryTableNotExistingGiven_shouldReturnOnlyDefault()
      throws Exception {
    // Arrange
    when(admin.tableExists(BASE_NAMESPACE, AuditorInternalValues.NAMESPACE_TABLE_NAME))
        .thenReturn(false);

    // Act
    List<String> namespaces = discovery.discoverLogicalNamespaces();

    // Assert
    assertThat(namespaces).containsExactly("default");
  }

  @Test
  void discoverLogicalNamespaces_registryWithTenantAGiven_shouldReturnDefaultAndTenantA()
      throws Exception {
    // Arrange
    when(admin.tableExists(BASE_NAMESPACE, AuditorInternalValues.NAMESPACE_TABLE_NAME))
        .thenReturn(true);
    Result tenantA = createMockNamespaceResult("tenant_a");
    Scanner scanner = mock(Scanner.class);
    when(scanner.iterator()).thenReturn(Arrays.asList(tenantA).iterator());
    when(storage.scan(any(Scan.class))).thenReturn(scanner);

    // Act
    List<String> namespaces = discovery.discoverLogicalNamespaces();

    // Assert
    assertThat(namespaces).containsExactly("default", "tenant_a");
    assertThat(
            namespaces.stream()
                .map(n -> AuditorInternalValues.resolveNamespace(BASE_NAMESPACE, n))
                .collect(Collectors.toList()))
        .containsExactly("auditor", "auditor_tenant_a");
  }
}
