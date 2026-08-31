#!/usr/bin/env bash
#
# Scenario: every command in run-cleanup.sh, in the documented apply order, against a cluster
# holding both committed data and stranded Auditor locks.
#
# Required environment:
#   CLEANUP_VERSION  tag of the scalardl-cleanup image
#   CLIENT           path to the ScalarDL HashStore CLI
#   COSMOSDB_SHELL   path to the Azure Cosmos DB Shell binary, used to count rows in Cosmos
#   RECORD_COUNT     records generated per category
#   RUNNER_TEMP      scratch directory holding the populated-assets metadata
# and a kubectl context with the ledger-e2e / auditor-e2e namespaces deployed.

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
E2E="$HERE/.."
source "$E2E/common.sh"
source "$E2E/port-forward.sh"
source "$E2E/run-cleanup.sh"

# client.properties resolves the Auditor CA cert relative to scalardl-cleanup/.
cd "$E2E/../.."

props="$E2E/client.properties"

require_vars CLIENT RUNNER_TEMP RECORD_COUNT

echo "== Run the cleanup commands =="

init_cleanup_commands

token_l=$(run_finalize_ledger)

rp_before=$(count_cosmos_items "$auditor_uri" "$auditor_key" auditor request_proof)
token_a=$(run_finalize_auditor)
rp_after=$(count_cosmos_items "$auditor_uri" "$auditor_key" auditor request_proof)
echo "request_proof rows: before=$rp_before after=$rp_after"
[ "$rp_after" -eq 0 ] || { echo "::error::finalize-auditor left $rp_after request_proof rows (expected 0)"; exit 1; }

cs_before=$(count_cosmos_items "$ledger_uri" "$ledger_key" coordinator state)
coord_out=$(run_cleanup_coordinator "$token_l" "$token_a")
echo "cleanup-coordinator output: $coord_out"
[ "$(printf '%s' "$coord_out" | jq -r '.status_code')" = "OK" ] \
  || { echo "::error::cleanup-coordinator did not report OK"; exit 1; }

cs_after=$(count_cosmos_items "$ledger_uri" "$ledger_key" coordinator state)
# Only the RECORD_COUNT committed put transactions are settled before the deletable-before boundary,
# so exactly those are removed. finalize-auditor's recovery aborts each stranded lock's nonce,
# writing coordinator.state rows after the boundary, which survive and are not counted here.
echo "coordinator.state rows: before=$cs_before after=$cs_after (expected deleted=$RECORD_COUNT)"
[ "$((cs_before - cs_after))" -eq "$RECORD_COUNT" ] \
  || { echo "::error::cleanup-coordinator deleted $((cs_before - cs_after)) coordinator.state rows (expected $RECORD_COUNT)"; exit 1; }
[ "$cs_after" -eq "$((2 * RECORD_COUNT))" ] \
  || { echo "::error::coordinator.state has $cs_after rows after cleanup (expected $((2 * RECORD_COUNT)))"; exit 1; }

echo "== Verify post-cleanup consistency =="
pf_reset
pf_start_all
populated="$RUNNER_TEMP/populated-assets.json"

# Read the ids up front rather than piping jq straight into the loop: a process substitution's exit
# status is invisible to `set -e`, so a missing or malformed file would run the loop zero times and
# report success. An empty list means the same thing, and is just as fatal.
[ -s "$populated" ] || { echo "::error::$populated is missing or empty"; exit 1; }
ids=$(jq -r '(.committedAssetIds + .strandedAssetIds)[]' "$populated") \
  || { echo "::error::could not read the asset ids from $populated"; exit 1; }
[ -n "$ids" ] || { echo "::error::$populated lists no asset ids to verify"; exit 1; }
count=$(printf '%s\n' "$ids" | wc -l | tr -d ' ')
# Both populated categories are RECORD_COUNT long, so a short list means a truncated file.
[ "$count" -eq "$((2 * RECORD_COUNT))" ] \
  || { echo "::error::$populated lists $count asset ids (expected $((2 * RECORD_COUNT)))"; exit 1; }
echo "Verifying $count assets ..."

# After cleanup, every asset must be fully usable again: writable, readable, and passing
# ledger validation.
while IFS= read -r id; do
  hash=$(printf '%s' "$id" | sha256sum | cut -d' ' -f1)
  if ! "$CLIENT" put-object --properties "$props" --object-id "$id" --hash "$hash"; then
    echo "::error::put-object on $id failed after cleanup"; exit 1
  fi
  if ! "$CLIENT" get-object --properties "$props" --object-id "$id"; then
    echo "::error::get-object on $id failed after cleanup"; exit 1
  fi
  if ! "$CLIENT" validate-ledger --properties "$props" --object-id "$id"; then
    echo "::error::validate-ledger on $id failed after cleanup"; exit 1
  fi
done <<< "$ids"

echo "Post-cleanup consistency verified."
