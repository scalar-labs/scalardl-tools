#!/usr/bin/env bash
#
# Creates non-terminal records outside asset and asset_metadata. Source it:
#
#   source ci/e2e/db-records.sh
#
# The records are written by the fixtures module's CLI, connected with the Ledger's own ScalarDB
# configuration so that it shares the Coordinator table with ScalarDL. It writes the PREPARED and
# DELETED records straight through the storage, as a writer that died between prepare and commit
# leaves them; ScalarDB 3.20 removed the two-phase commit API this used to prepare them with.
#
# With E2E_STORAGE=multi-storage (common.sh) the fixture has a second table, in the cluster's
# PostgreSQL, holding the same records. The CLI writes it with fixture-multistorage.properties,
# which keeps the Coordinator table in the Ledger's Cosmos DB account. Every function here covers
# both tables unless it is given namespaces of its own.
#
# Required environment:
#   RUNNER_TEMP        scratch directory for the CLI's logs and its rendered configuration
#   FIXTURES           path to the fixtures CLI (./gradlew :fixtures:installDist)
#   COSMOSDB_SHELL     needed by the counting helpers, which check for it themselves
# plus $ledger_props / $ledger_uri / $ledger_key, which load_ad_credentials (cleanup-jobs.sh) sets.

E2E_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

source "$E2E_DIR/cleanup-jobs.sh"
source "$E2E_DIR/port-forward.sh"

# Exposed because a scenario counts rows in Cosmos itself. The PostgreSQL table has the same name,
# in the namespace $POSTGRES_NAMESPACE (common.sh).
DB_NS="e2e_db"
DB_TABLE="records"

# The namespaces of the fixture's tables under the current storage configuration.
DB_NAMESPACES=("$DB_NS")
if [ "$E2E_STORAGE" = multi-storage ]; then
  DB_NAMESPACES+=("$POSTGRES_NAMESPACE")
fi

# Mirrors RecoveryHandler.TRANSACTION_LIFETIME_MILLIS in ScalarDB: how long recovery leaves a
# prepared record alone.
RECOVERY_EXPIRY_SECS=15

# ScalarDB's TransactionState, as stored in tx_state.
DB_TX_STATE_PREPARED=1
DB_TX_STATE_DELETED=2
DB_TX_STATE_COMMITTED=3

# What the fixture leaves in the Coordinator table per table, for a scenario's arithmetic: populate
# commits one transaction and leaves two prepared, which finalize-ledger aborts with a record each.
# A scenario that runs under either storage configuration multiplies them by ${#DB_NAMESPACES[@]}.
DB_SETTLED_COORDINATOR_ROWS=1
DB_ABORTED_COORDINATOR_ROWS=2

# Check what the fixtures CLI needs.
# Usage: db_records_init
db_records_init() {
  require_vars RUNNER_TEMP FIXTURES
  if [ -z "${ledger_props:-}" ] || [ ! -f "$ledger_props" ]; then
    echo "::error::db_records_init needs load_ad_credentials to have run first" >&2
    exit 1
  fi
}

# Internal helper. Run a fixtures subcommand against one of the fixture's tables and fail if it did.
# Usage: db_records_fixture <namespace> <subcommand> [args...]
db_records_fixture() {
  local ns="$1" sub="$2" config="$ledger_props" pf_pid="" status=0
  local log="$RUNNER_TEMP/db-records-$ns-$sub.log"
  shift
  if [ "$ns" = "$POSTGRES_NAMESPACE" ]; then
    config="$RUNNER_TEMP/fixture-multistorage.properties"
    # FIXTURE_* only exist for this substitution; the rest of the list is exported already.
    FIXTURE_COSMOS_URI="$ledger_uri" FIXTURE_COSMOS_KEY="$ledger_key" envsubst \
      '${FIXTURE_COSMOS_URI} ${FIXTURE_COSMOS_KEY} ${POSTGRES_LOCAL_PORT} ${POSTGRES_DB}
       ${POSTGRES_USER} ${POSTGRES_PASSWORD} ${POSTGRES_NAMESPACE}' \
      < "$E2E_DIR/fixture-multistorage.properties" > "$config"
    # Forwarded for this call only: pf_reset, which populate.sh runs between the fixture's steps,
    # kills every forward into the Ledger's namespace.
    pf_start "$LEDGER_NS" "svc/$POSTGRES_SERVICE" "$POSTGRES_LOCAL_PORT:$POSTGRES_PORT"
    pf_pid=$!
    pf_wait "$POSTGRES_LOCAL_PORT"
  fi
  "$FIXTURES" --config "$config" --namespace "$ns" --table "$DB_TABLE" "$@" \
    > "$log" 2>&1 || status=$?
  if [ -n "$pf_pid" ]; then
    kill "$pf_pid" 2>/dev/null || true
    # Reaped here so that the shell does not report the termination in the middle of a later step.
    wait "$pf_pid" 2>/dev/null || true
  fi
  if [ "$status" -ne 0 ]; then
    echo "::error::fixtures $sub failed for $ns.$DB_TABLE (exit status $status)"
    cat "$log"
    exit 1
  fi
  cat "$log"
}

# Create the tables if they are not there, and empty them if they are.
# Usage: db_records_reset_schema
db_records_reset_schema() {
  local ns
  for ns in "${DB_NAMESPACES[@]}"; do
    db_records_fixture "$ns" reset-schema
  done
}

# Write <count> records in each category to each table: committed ones in one transaction, and the
# PREPARED and DELETED ones as two transactions that never got past prepare. See FixtureMain for the
# details. The tables get transactions of their own, so the Coordinator table gets
# DB_SETTLED_COORDINATOR_ROWS per table.
# Usage: db_records_populate <count>
db_records_populate() {
  local ns
  for ns in "${DB_NAMESPACES[@]}"; do
    db_records_fixture "$ns" populate --count "$1"
  done
}

# Run a query that returns a single number against the cluster's PostgreSQL, and echo it. psql runs
# in the PostgreSQL Pod itself, so this depends on neither a client on the runner nor a
# port-forward.
# Usage: count_postgres_records_by_query <query>
count_postgres_records_by_query() {
  local count
  count=$(kubectl -n "$LEDGER_NS" exec "deploy/$POSTGRES_SERVICE" -- \
    psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 -At -c "$1")
  if ! [[ "$count" =~ ^[0-9]+$ ]]; then
    echo "::error::could not parse a row count from psql output: $count" >&2
    return 1
  fi
  printf '%s\n' "$count"
}

# Internal helper. Reads the storage directly: a transactional read cannot see tx_state and would
# trigger recovery. The JDBC storage keeps a namespace as a schema and quotes every identifier.
# Usage: db_records_count <namespace> <category> [tx_state]
db_records_count() {
  local ns="$1" category="$2" state="${3:-}"
  if [ "$ns" = "$POSTGRES_NAMESPACE" ]; then
    count_postgres_records_by_query \
      "SELECT COUNT(*) FROM \"$ns\".\"$DB_TABLE\" WHERE \"pk\" LIKE '$category-%'${state:+ AND \"tx_state\" = $state}"
  else
    count_cosmos_records_by_query "$ledger_uri" "$ledger_key" "$ns" "$DB_TABLE" \
      "SELECT VALUE COUNT(1) FROM c WHERE STARTSWITH(c.partitionKey.pk, '$category-')${state:+ AND c.values.tx_state = $state}"
  fi
}

# Count the records of one of the fixture's tables left non-terminal.
# Usage: db_records_count_unsettled <namespace>
db_records_count_unsettled() {
  local ns="$1"
  if [ "$ns" = "$POSTGRES_NAMESPACE" ]; then
    count_postgres_records_by_query \
      "SELECT COUNT(*) FROM \"$ns\".\"$DB_TABLE\" WHERE \"tx_state\" IN ($DB_TX_STATE_PREPARED, $DB_TX_STATE_DELETED)"
  else
    count_cosmos_unsettled_records "$ledger_uri" "$ledger_key" "$ns" "$DB_TABLE"
  fi
}

# Abort unless each table holds exactly <expected> non-terminal records.
# Usage: db_records_assert_unsettled <expected> [namespace...]
db_records_assert_unsettled() {
  local expected="$1" ns actual
  shift
  [ "$#" -gt 0 ] || set -- "${DB_NAMESPACES[@]}"
  for ns in "$@"; do
    actual=$(db_records_count_unsettled "$ns")
    echo "$ns.$DB_TABLE non-terminal records: $actual"
    [ "$actual" -eq "$expected" ] || {
      echo "::error::$ns.$DB_TABLE holds $actual non-terminal records (expected $expected)"
      exit 1
    }
  done
}

# Internal helper.
# Usage: db_records_assert_count <namespace> <category> <expected> [tx_state]
db_records_assert_count() {
  local ns="$1" category="$2" expected="$3" state="${4:-}" actual
  actual=$(db_records_count "$ns" "$category" "$state")
  [ "$actual" -eq "$expected" ] || {
    echo "::error::$ns.$DB_TABLE holds $actual $category records${state:+ in tx_state $state} (expected $expected)"
    exit 1
  }
}

# Check every category of each table against the state the given phase requires.
# Usage: db_records_verify <populated|finalized> <count> [namespace...]
db_records_verify() {
  local phase="$1" count="$2" ns
  shift 2
  [ "$#" -gt 0 ] || set -- "${DB_NAMESPACES[@]}"
  for ns in "$@"; do
    case "$phase" in
      populated)
        db_records_assert_count "$ns" committed "$count" "$DB_TX_STATE_COMMITTED"
        db_records_assert_count "$ns" prepared "$count" "$DB_TX_STATE_PREPARED"
        db_records_assert_count "$ns" deleted "$count" "$DB_TX_STATE_DELETED"
        ;;
      finalized)
        db_records_assert_count "$ns" committed "$count" "$DB_TX_STATE_COMMITTED"
        db_records_assert_count "$ns" prepared 0
        db_records_assert_count "$ns" deleted "$count" "$DB_TX_STATE_COMMITTED"
        ;;
      *)
        echo "::error::unknown phase '$phase'; expected populated or finalized" >&2
        exit 1
        ;;
    esac
    echo "$ns.$DB_TABLE holds what phase=$phase requires: $count records in each of 3 categories"
  done
}

# Usage: db_records_drop_schema
db_records_drop_schema() {
  local ns
  for ns in "${DB_NAMESPACES[@]}"; do
    db_records_fixture "$ns" drop-schema
  done
}
