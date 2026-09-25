#!/usr/bin/env bash
#
# Creates non-terminal records outside asset and asset_metadata. Source it:
#
#   source ci/e2e/db-records.sh
#
# It uses a ScalarDB Cluster instance sharing the Coordinator table with ScalarDL, driven with the
# SQL CLI: running PREPARE without COMMIT is what leaves the records non-terminal.
#
# Required environment:
#   RUNNER_TEMP        scratch directory for the rendered manifest, the CLI config and the scripts
#   SCALARDB_SQL_CLI   path to the ScalarDB Cluster SQL CLI jar
#   SCALARDB_VERSION   tag of the scalardb-cluster-node image
#   COSMOSDB_SHELL     needed by db_records_verify, which checks for it itself
# plus $ledger_uri / $ledger_key, which load_ad_credentials (cleanup-jobs.sh) sets.

E2E_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

source "$E2E_DIR/cleanup-jobs.sh"
source "$E2E_DIR/port-forward.sh"

# Exposed because a scenario counts rows in Cosmos itself.
DB_NS="e2e_db"
DB_TABLE="records"

# Mirrors RecoveryHandler.TRANSACTION_LIFETIME_MILLIS in ScalarDB: how long recovery leaves a
# prepared record alone.
RECOVERY_EXPIRY_SECS=15

# The cluster node's gRPC port, forwarded to the same port locally.
DB_CLUSTER_PORT=60053

# ScalarDB's TransactionState, as stored in tx_state.
DB_TX_STATE_PREPARED=1
DB_TX_STATE_DELETED=2
DB_TX_STATE_COMMITTED=3

# What the fixture leaves in the Coordinator table, for a scenario's arithmetic: populate commits
# one transaction and leaves two prepared, which finalize-ledger aborts with a record each.
DB_SETTLED_COORDINATOR_ROWS=1
DB_ABORTED_COORDINATOR_ROWS=2

# Deploy the cluster node the SQL below runs against, forward its port, and write the CLI's config.
# Usage: db_records_sql_init
db_records_sql_init() {
  require_vars RUNNER_TEMP SCALARDB_SQL_CLI SCALARDB_VERSION
  if [ -z "${ledger_uri:-}" ] || [ -z "${ledger_key:-}" ]; then
    echo "::error::db_records_sql_init needs load_ad_credentials to have run first" >&2
    exit 1
  fi

  # The node reads the account key as SCALAR_DB_PASSWORD, which the manifest pulls in with envFrom.
  kubectl -n "$LEDGER_NS" create secret generic scalardb-cluster-credentials \
    --from-literal=SCALAR_DB_PASSWORD="$ledger_key" \
    --dry-run=client -o yaml | kubectl apply -f -

  # Recreate the node: the schema reset leaves its Cosmos SDK caching a container that is gone.
  kubectl -n "$LEDGER_NS" delete deployment scalardb-cluster --ignore-not-found --wait
  COSMOS_URI="$ledger_uri" SCALARDB_VERSION="$SCALARDB_VERSION" \
    envsubst '${LEDGER_NS} ${SCALARDB_VERSION} ${COSMOS_URI}' < "$E2E_DIR/scalardb-cluster.yaml" \
    | kubectl apply -f -
  if ! kubectl -n "$LEDGER_NS" rollout status deployment/scalardb-cluster --timeout=300s; then
    diag_and_die "$LEDGER_NS" "the ScalarDB Cluster node the fixture writes through did not become ready"
  fi

  # Start the forward fresh: this runs once per fixture rebuild, and the one the last run started
  # may still be up, pointing at a Pod that is gone.
  pkill -f "kubectl.*port-forward.*scalardb-cluster" 2>/dev/null || true
  sleep 2
  pf_start "$LEDGER_NS" svc/scalardb-cluster "$DB_CLUSTER_PORT:$DB_CLUSTER_PORT"
  pf_wait "$DB_CLUSTER_PORT"

  db_records_config="$RUNNER_TEMP/db-records-sql.properties"
  cat > "$db_records_config" <<EOF
scalar.db.sql.connection_mode=cluster
scalar.db.sql.cluster_mode.contact_points=indirect:localhost
scalar.db.sql.cluster_mode.contact_port=$DB_CLUSTER_PORT
scalar.db.sql.default_transaction_mode=two_phase_commit_transaction
EOF
}

# Internal helper. Run a SQL script through the CLI and fail if any statement did. The exit status
# alone is not enough -- the CLI returns 0 even when a statement failed, reporting it as an "Error:"
# line -- and neither is the log, since a launch failure need not print one.
# Usage: db_records_sql <script-file>
db_records_sql() {
  local name log status=0
  name="$(basename "$1" .sql)"
  log="$RUNNER_TEMP/$name.log"
  java -jar "$SCALARDB_SQL_CLI" --config "$db_records_config" --file "$1" > "$log" 2>&1 || status=$?
  if [ "$status" -ne 0 ] || grep -qE '^Error' "$log"; then
    echo "::error::$name failed (exit status $status)"
    cat "$log"
    exit 1
  fi
  echo "$name ran to completion"
}

# Create the table if it is not there, and empty it if it is. Truncating rather than dropping and
# recreating: a recreated Cosmos container gets a new resource id, which the long-lived node's SDK
# would go on answering from its cache with a 410.
# Usage: db_records_reset_schema
db_records_reset_schema() {
  local script="$RUNNER_TEMP/db-records-reset-schema.sql"
  cat > "$script" <<EOF
CREATE NAMESPACE IF NOT EXISTS $DB_NS;
CREATE TABLE IF NOT EXISTS $DB_NS.$DB_TABLE (pk TEXT PRIMARY KEY, val TEXT);
TRUNCATE TABLE $DB_NS.$DB_TABLE;
EOF
  db_records_sql "$script"
}

# Write <count> records in each category, two of the three left non-terminal. Two sessions, because
# the first still owns the transaction it leaves prepared.
# Usage: db_records_populate <count>
db_records_populate() {
  local count="$1" i
  local settle="$RUNNER_TEMP/db-records-populate.sql"
  local hang="$RUNNER_TEMP/db-records-prepare-deletes.sql"

  {
    # The committed rows, and the rows the prepared deletes below go on to target.
    echo "BEGIN;"
    for i in $(seq 0 $((count - 1))); do
      echo "INSERT INTO $DB_NS.$DB_TABLE (pk, val) VALUES ('committed-$i', 'value-committed-$i');"
      echo "INSERT INTO $DB_NS.$DB_TABLE (pk, val) VALUES ('deleted-$i', 'value-deleted-$i');"
    done
    echo "PREPARE;"
    echo "VALIDATE;"
    echo "COMMIT;"
    # Abandoned after PREPARE, which is what a writer that died mid-commit leaves behind.
    echo "BEGIN;"
    for i in $(seq 0 $((count - 1))); do
      echo "INSERT INTO $DB_NS.$DB_TABLE (pk, val) VALUES ('prepared-$i', 'value-prepared-$i');"
    done
    echo "PREPARE;"
  } > "$settle"
  db_records_sql "$settle"

  {
    # A prepared delete marks the record DELETED rather than removing it; aborting rolls it back.
    echo "BEGIN;"
    for i in $(seq 0 $((count - 1))); do
      echo "DELETE FROM $DB_NS.$DB_TABLE WHERE pk = 'deleted-$i';"
    done
    echo "PREPARE;"
  } > "$hang"
  db_records_sql "$hang"
}

# Internal helper. Reads Cosmos, not SQL: SQL cannot see tx_state and a cluster read triggers
# recovery.
# Usage: db_records_count <category> [tx_state]
db_records_count() {
  local category="$1" state="${2:-}" filter=""
  if [ -n "$state" ]; then
    filter=" AND c.values.tx_state = $state"
  fi
  count_cosmos_records_by_query "$ledger_uri" "$ledger_key" "$DB_NS" "$DB_TABLE" \
    "SELECT VALUE COUNT(1) FROM c WHERE STARTSWITH(c.partitionKey.pk, '$category-')$filter"
}

# Internal helper.
# Usage: db_records_assert_count <category> <expected> [tx_state]
db_records_assert_count() {
  local category="$1" expected="$2" state="${3:-}" actual
  actual=$(db_records_count "$category" "$state")
  [ "$actual" -eq "$expected" ] || {
    echo "::error::$DB_NS.$DB_TABLE holds $actual $category records${state:+ in tx_state $state} (expected $expected)"
    exit 1
  }
}

# Check every category against the state the given phase requires.
# Usage: db_records_verify <populated|finalized> <count>
db_records_verify() {
  local phase="$1" count="$2"
  case "$phase" in
    populated)
      db_records_assert_count committed "$count" "$DB_TX_STATE_COMMITTED"
      db_records_assert_count prepared "$count" "$DB_TX_STATE_PREPARED"
      db_records_assert_count deleted "$count" "$DB_TX_STATE_DELETED"
      ;;
    finalized)
      db_records_assert_count committed "$count" "$DB_TX_STATE_COMMITTED"
      db_records_assert_count prepared 0
      db_records_assert_count deleted "$count" "$DB_TX_STATE_COMMITTED"
      ;;
    *)
      echo "::error::unknown phase '$phase'; expected populated or finalized" >&2
      exit 1
      ;;
  esac
  echo "$DB_NS.$DB_TABLE holds what phase=$phase requires: $count records in each of 3 categories"
}

# Usage: db_records_drop_schema
db_records_drop_schema() {
  local script="$RUNNER_TEMP/db-records-drop-schema.sql"
  cat > "$script" <<EOF
DROP NAMESPACE IF EXISTS $DB_NS CASCADE;
EOF
  db_records_sql "$script"
}
