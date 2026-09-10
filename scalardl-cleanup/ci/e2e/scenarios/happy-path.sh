#!/usr/bin/env bash
#
# Scenario: every command in run-cleanup.sh, in the documented apply order, against a cluster
# holding both committed data and stranded Auditor locks.
#
# New traffic runs once the finalize commands have taken their completion tokens, because that is
# the normal way to run the tool: the servers keep serving. The tokens fix the deletable-before
# boundary, so the cleanup that follows has to leave that transaction state alone.
#
# Required environment:
#   CLEANUP_VERSION           tag of the scalardl-cleanup image
#   COSMOSDB_SHELL            path to the Azure Cosmos DB Shell binary, used to count rows in Cosmos
#   RECORD_COUNT              records generated per category
#   RUNNER_TEMP               scratch directory for the rendered manifests and the populated-assets
#                             metadata
#   SCALARDL_NEW_VERSION      the version the cleanup commands coordinate with
# plus what setup-fixture.sh and populate.sh need, and a kubectl context with the namespaces
# deployed.

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
E2E="$HERE/.."
source "$E2E/run-cleanup.sh"

require_vars RUNNER_TEMP RECORD_COUNT SCALARDL_NEW_VERSION

echo "== Rebuild the fixture =="
"$E2E/setup-fixture.sh"

echo "== Upgrade the servers to $SCALARDL_NEW_VERSION =="
SCALARDL_VERSION="$SCALARDL_NEW_VERSION" "$E2E/manage-cluster.sh" change-version

echo "== Run the finalize commands and check what they reclaimed =="

init_cleanup_commands

run_finalize_ledger

rp_before=$(count_cosmos_records "$auditor_uri" "$auditor_key" auditor request_proof)
run_finalize_auditor
rp_after=$(count_cosmos_records "$auditor_uri" "$auditor_key" auditor request_proof)
echo "request_proof rows: before=$rp_before after=$rp_after"
[ "$rp_after" -eq 0 ] || { echo "::error::finalize-auditor left $rp_after request_proof rows (expected 0)"; exit 1; }
held=$(count_cosmos_held_locks "$auditor_uri" "$auditor_key" auditor)
[ "$held" -eq 0 ] || { echo "::error::finalize-auditor left $held asset locks held"; exit 1; }

echo "== Run new traffic now that both tokens are taken =="
"$E2E/populate.sh" commit-post-token-objects

rp_before_cleanup=$(count_cosmos_records "$auditor_uri" "$auditor_key" auditor request_proof)
# The new traffic has to have written state, or the assertions below prove nothing.
[ "$rp_before_cleanup" -gt 0 ] \
  || { echo "::error::the new traffic left no request_proof rows"; exit 1; }

echo "== Run cleanup-coordinator and check what it reclaimed =="

cs_before=$(count_cosmos_records "$ledger_uri" "$ledger_key" coordinator state)
run_cleanup_coordinator "$ledger_token" "$auditor_token"
[ "$(printf '%s' "$cleanup_coordinator_output" | jq -r '.status_code')" = "OK" ] \
  || { echo "::error::cleanup-coordinator did not report OK"; exit 1; }

cs_after=$(count_cosmos_records "$ledger_uri" "$ledger_key" coordinator state)
# Only the RECORD_COUNT committed put transactions are settled before the deletable-before boundary,
# so exactly those are removed. What survives is everything written after it: finalize-auditor
# aborted each stranded lock's nonce (2 * RECORD_COUNT), and the new traffic committed RECORD_COUNT
# more.
echo "coordinator.state rows: before=$cs_before after=$cs_after (expected deleted=$RECORD_COUNT)"
[ "$((cs_before - cs_after))" -eq "$RECORD_COUNT" ] \
  || { echo "::error::cleanup-coordinator deleted $((cs_before - cs_after)) coordinator.state rows (expected $RECORD_COUNT)"; exit 1; }
[ "$cs_after" -eq "$((3 * RECORD_COUNT))" ] \
  || { echo "::error::coordinator.state has $cs_after rows after cleanup (expected $((3 * RECORD_COUNT)))"; exit 1; }

# cleanup-coordinator deletes Coordinator state only; the request proofs the new traffic wrote are
# not its to touch.
rp_after_cleanup=$(count_cosmos_records "$auditor_uri" "$auditor_key" auditor request_proof)
echo "request_proof rows: before=$rp_before_cleanup after=$rp_after_cleanup"
[ "$rp_after_cleanup" -eq "$rp_before_cleanup" ] \
  || { echo "::error::cleanup-coordinator changed request_proof from $rp_before_cleanup to $rp_after_cleanup"; exit 1; }

# Nothing may still be pointing at the Coordinator records that were just deleted.
for table in asset asset_metadata; do
  unsettled=$(count_cosmos_unsettled_records "$ledger_uri" "$ledger_key" scalar "$table")
  [ "$unsettled" -eq 0 ] \
    || { echo "::error::$table holds $unsettled records left mid-transaction"; exit 1; }
done
echo "no records left mid-transaction in asset or asset_metadata"

echo "== Verify the contracts still run =="
# Three categories of RECORD_COUNT: the committed data, the stranded ids, and the new traffic.
"$E2E/populate.sh" verify-assets "$((3 * RECORD_COUNT))"

echo "Every command reclaimed what it should and spared what the tokens put out of reach."
