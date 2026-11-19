package com.scalar.dl.tools.cleanup.fixtures;

import com.scalar.db.api.Delete;
import com.scalar.db.api.DistributedStorage;
import com.scalar.db.api.DistributedStorageAdmin;
import com.scalar.db.api.DistributedTransaction;
import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.api.DistributedTransactionManager;
import com.scalar.db.api.Insert;
import com.scalar.db.api.TableMetadata;
import com.scalar.db.io.DataType;
import com.scalar.db.io.Key;
import com.scalar.db.service.StorageFactory;
import com.scalar.db.service.TransactionFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * The E2E's way to leave records non-terminal in a table of its own, outside ScalarDL's tables.
 *
 * <p>It connects with the Ledger's own ScalarDB configuration, so it shares the Coordinator table
 * with Ledger, and writes three categories of records keyed {@code <category>-<i>}:
 *
 * <ul>
 *   <li>{@code committed}: committed, left alone by finalize-ledger;
 *   <li>{@code prepared}: inserted as PREPARED by one transaction, which aborting deletes;
 *   <li>{@code deleted}: committed, then marked DELETED by another transaction, which aborting
 *       restores.
 * </ul>
 *
 * <p>The committed records are written in a single transaction, so the Coordinator table gets one
 * COMMITTED state; the two non-terminal transactions get none, as after a crash between prepare and
 * commit, and finalize-ledger aborts each.
 */
@Command(
    name = "fixtures",
    mixinStandardHelpOptions = true,
    description = "Leaves non-terminal records for the E2E.")
public final class FixtureMain {

  @Option(
      names = "--config",
      required = true,
      description = "ScalarDB properties file, the Ledger's own.")
  private Path config;

  @Option(names = "--namespace", required = true, description = "Namespace of the table.")
  private String namespace;

  @Option(names = "--table", required = true, description = "Table name.")
  private String table;

  public static void main(String[] args) {
    System.exit(new CommandLine(new FixtureMain()).execute(args));
  }

  @Command(
      name = "reset-schema",
      description = "Create the table if it is not there, and empty it if it is.")
  int resetSchema() throws Exception {
    return withAdmin(
        admin -> {
          admin.createNamespace(namespace, true);
          admin.createTable(namespace, table, tableMetadata(), true);
          admin.truncateTable(namespace, table);
          System.out.printf("%s.%s is empty%n", namespace, table);
          return 0;
        });
  }

  @Command(name = "populate", description = "Write <count> records in each category.")
  int populate(@Option(names = "--count", required = true) int count) throws Exception {
    Properties props = loadProperties();
    TransactionFactory transactionFactory = TransactionFactory.create(props);
    StorageFactory storageFactory = StorageFactory.create(props);
    DistributedTransactionManager manager = transactionFactory.getTransactionManager();
    DistributedStorage storage = storageFactory.getStorage();
    DistributedStorageAdmin storageAdmin = storageFactory.getStorageAdmin();
    try {
      // The committed records, and the records the DELETED ones below go on to target.
      DistributedTransaction tx = manager.begin();
      for (int i = 0; i < count; i++) {
        tx.insert(insert("committed-" + i));
        tx.insert(insert("deleted-" + i));
      }
      tx.commit();

      NonTerminalRecordWriter writer = new NonTerminalRecordWriter(storage, storageAdmin);
      String preparedTxId = NonTerminalRecordWriter.newTransactionId();
      for (int i = 0; i < count; i++) {
        writer.writePreparedInsert(preparedTxId, insert("prepared-" + i));
      }
      String deletedTxId = NonTerminalRecordWriter.newTransactionId();
      for (int i = 0; i < count; i++) {
        writer.writePreparedDelete(deletedTxId, delete("deleted-" + i));
      }

      System.out.printf(
          "%s.%s: %d records committed by %s, PREPARED by %s, DELETED by %s%n",
          namespace, table, count, tx.getId(), preparedTxId, deletedTxId);
      return 0;
    } finally {
      storageAdmin.close();
      storage.close();
      manager.close();
    }
  }

  @Command(name = "drop-schema", description = "Drop the table and its namespace.")
  int dropSchema() throws Exception {
    return withAdmin(
        admin -> {
          admin.dropTable(namespace, table, true);
          admin.dropNamespace(namespace, true);
          System.out.printf("%s.%s is dropped%n", namespace, table);
          return 0;
        });
  }

  private interface AdminTask {
    int run(DistributedTransactionAdmin admin) throws Exception;
  }

  private int withAdmin(AdminTask task) throws Exception {
    DistributedTransactionAdmin admin =
        TransactionFactory.create(loadProperties()).getTransactionAdmin();
    try {
      return task.run(admin);
    } finally {
      admin.close();
    }
  }

  private Properties loadProperties() throws Exception {
    Properties props = new Properties();
    try (InputStream in = Files.newInputStream(config)) {
      props.load(in);
    }
    return props;
  }

  private static TableMetadata tableMetadata() {
    return TableMetadata.newBuilder()
        .addColumn("pk", DataType.TEXT)
        .addColumn("val", DataType.TEXT)
        .addPartitionKey("pk")
        .build();
  }

  private Insert insert(String pk) {
    return Insert.newBuilder()
        .namespace(namespace)
        .table(table)
        .partitionKey(Key.ofText("pk", pk))
        .textValue("val", "value-" + pk)
        .build();
  }

  private Delete delete(String pk) {
    return Delete.newBuilder()
        .namespace(namespace)
        .table(table)
        .partitionKey(Key.ofText("pk", pk))
        .build();
  }

  private FixtureMain() {}
}
