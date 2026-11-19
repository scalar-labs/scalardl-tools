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
# Required environment:
#   RUNNER_TEMP        scratch directory for the CLI's logs
#   FIXTURES           path to the fixtures CLI (./gradlew :fixtures:installDist)
#   COSMOSDB_SHELL     needed by db_records_verify, which checks for it itself
# plus $ledger_props / $ledger_uri / $ledger_key, which load_ad_credentials (cleanup-jobs.sh) sets.

E2E_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

source "$E2E_DIR/cleanup-jobs.sh"

# Exposed because a scenario counts rows in Cosmos itself.
DB_NS="e2e_db"
DB_TABLE="records"

# Mirrors RecoveryHandler.TRANSACTION_LIFETIME_MILLIS in ScalarDB: how long recovery leaves a
# prepared record alone.
RECOVERY_EXPIRY_SECS=15

# ScalarDB's TransactionState, as stored in tx_state.
DB_TX_STATE_PREPARED=1
DB_TX_STATE_DELETED=2
DB_TX_STATE_COMMITTED=3

# What the fixture leaves in the Coordinator table, for a scenario's arithmetic: populate commits
# one transaction and leaves two prepared, which finalize-ledger aborts with a record each.
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

# Internal helper. Run a fixtures subcommand against the fixture's table and fail if it did.
# Usage: db_records_fixture <subcommand> [args...]
db_records_fixture() {
  local log="$RUNNER_TEMP/db-records-$1.log" status=0
  "$FIXTURES" --config "$ledger_props" --namespace "$DB_NS" --table "$DB_TABLE" "$@" \
    > "$log" 2>&1 || status=$?
  if [ "$status" -ne 0 ]; then
    echo "::error::fixtures $1 failed (exit status $status)"
    cat "$log"
    exit 1
  fi
  cat "$log"
}

# Create the table if it is not there, and empty it if it is.
# Usage: db_records_reset_schema
db_records_reset_schema() {
  db_records_fixture reset-schema
}

# Write <count> records in each category: committed ones in one transaction, and the PREPARED and
# DELETED ones as two transactions that never got past prepare. See FixtureMain for the details.
# Usage: db_records_populate <count>
db_records_populate() {
  db_records_fixture populate --count "$1"
}

# Internal helper. Reads Cosmos directly: a transactional read cannot see tx_state and would
# trigger recovery.
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
  db_records_fixture drop-schema
}
