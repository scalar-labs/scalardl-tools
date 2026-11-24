#!/usr/bin/env bash
#
# Scenario: finalize-ledger and verify-ledger-finalization told apart a multi-storage configuration
# that is wrong, or that is not the one the run started with, from one that is right.
#
# The fixture is the multi-storage one: non-terminal records in the Ledger's Cosmos DB account and
# in PostgreSQL. A configuration that does not reach the PostgreSQL table leaves its records
# non-terminal, and cleanup-coordinator would then delete the Coordinator state they need. So:
#
#   1. a namespace mapping still holding the shipped <namespace> placeholder is rejected
#      (DL-TOOLS-1020) before anything is finalized;
#   2. with Cosmos DB only, finalize-ledger completes without ever seeing the PostgreSQL table;
#   3. with multi-storage, verify-ledger-finalization then finds the records that run left;
#   4. finalize-ledger refuses to carry that run on with another storage configuration
#      (DL-TOOLS-1023);
#   5. started over on an empty checkpoint, it finalizes the PostgreSQL table too.
#
# Only the Ledger AD's configuration changes between the steps. cleanup-coordinator and the
# Auditor's commands have no part in this and are not run.
#
# Required environment:
#   CLEANUP_VERSION           tag of the scalardl-cleanup image
#   COSMOSDB_SHELL            path to the Azure Cosmos DB Shell binary, used to count rows in Cosmos
#   RECORD_COUNT              records generated per category
#   RUNNER_TEMP               scratch directory for the rendered manifests
#   SCALARDL_NEW_VERSION      the version the cleanup commands coordinate with
# plus what setup-fixture.sh and populate.sh need, and a kubectl context with the namespaces
# deployed.

set -euo pipefail

# The fixture has to have the PostgreSQL table whatever the caller's configuration is.
export E2E_STORAGE=multi-storage

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
E2E="$HERE/.."
source "$E2E/run-cleanup.sh"
source "$E2E/db-records.sh"

# Abort unless the finalize-ledger run that just failed reported the given error as the operator's
# to fix.
# Usage: assert_finalize_ledger_user_error <error-code>
assert_finalize_ledger_user_error() {
  local status message
  status=$(printf '%s' "$finalize_ledger_error" | jq -r '.status_code')
  [ "$status" = "USER_ERROR" ] \
    || { echo "::error::finalize-ledger reported status_code=$status (expected USER_ERROR)"; exit 1; }
  message=$(printf '%s' "$finalize_ledger_error" | jq -r '.error_message')
  case "$message" in
    "$1":*) ;;
    *) echo "::error::finalize-ledger reported '$message' (expected $1)"; exit 1 ;;
  esac
  echo "finalize-ledger failed as expected: $message"
}

require_vars RUNNER_TEMP RECORD_COUNT SCALARDL_NEW_VERSION

echo "== Rebuild the fixture =="
"$E2E/setup-fixture.sh"

echo "== Upgrade the servers to $SCALARDL_NEW_VERSION =="
SCALARDL_VERSION="$SCALARDL_NEW_VERSION" "$E2E/manage-cluster.sh" change-version

init_cleanup_commands

# Verify the non-terminal records are there first, in both tables, or nothing below proves anything.
unsettled=$((2 * RECORD_COUNT))
db_records_assert_unsettled "$unsettled"

echo "== 1. Run finalize-ledger with the namespace mapping's placeholder left in =="

ledger_ad_config=multi-storage-unmapped
run_finalize_ledger_expect_failure
assert_finalize_ledger_user_error DL-TOOLS-1020
# The check comes before any table is processed.
db_records_assert_unsettled "$unsettled"

echo "== 2. Run finalize-ledger with Cosmos DB only =="

# The rejected run left no checkpoint, so this one starts as a new run without a reset.
ledger_ad_config=cosmos
run_finalize_ledger
db_records_assert_unsettled 0 "$DB_NS"
db_records_verify finalized "$RECORD_COUNT" "$DB_NS"
db_records_assert_unsettled "$unsettled" "$POSTGRES_NAMESPACE"
db_records_verify populated "$RECORD_COUNT" "$POSTGRES_NAMESPACE"

echo "== 3. Run verify-ledger-finalization with multi-storage =="

ledger_ad_config=multi-storage
run_verify_ledger_finalization failed
assert_verification_failed verify-ledger-finalization

echo "== 4. Run finalize-ledger with multi-storage on the Cosmos DB run's checkpoint =="

run_finalize_ledger_expect_failure
assert_finalize_ledger_user_error DL-TOOLS-1023
db_records_assert_unsettled "$unsettled" "$POSTGRES_NAMESPACE"

echo "== 5. Start finalize-ledger over with multi-storage =="

reset_checkpoint "$LEDGER_NS"
run_finalize_ledger
db_records_assert_unsettled 0
db_records_verify finalized "$RECORD_COUNT"

run_verify_ledger_finalization passed
assert_verification_passed verify-ledger-finalization

echo "A wrong or changed storage configuration was caught, and the right one finalized both tables."
