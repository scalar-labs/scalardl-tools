#!/usr/bin/env bash
#
# Scenario: the operator's cleanup procedure against a cluster holding both committed data and
# stranded Auditor locks.
#
# It runs the three subcommands the way an operator deploys them -- as Kubernetes Jobs from
# scalardl-cleanup/manifests, in the documented apply order, so the deployment procedure is under
# test too:
#   finalize-ledger      -> emits the Ledger completion token
#   finalize-auditor     -> recovers the stranded asset locks via RecoverAssetLock and cleans up
#                           request_proof; emits the Auditor completion token
#   cleanup-coordinator  -> deletes the settled coordinator.state records
# and then asserts, at the ScalarDL application layer, that every populated asset is writable,
# readable, and passes validate-ledger.
#
# A scenario is a self-contained script that the workflow runs as a single step: it drives what it
# needs, asserts, and exits non-zero on the first failed assertion. Section markers are echoed so a
# failure is locatable within the step's log.
#
# Required environment: what run-cleanup.sh needs (CLEANUP_VERSION, COSMOSDB_SHELL, RUNNER_TEMP,
# RECORD_COUNT), plus CLIENT.

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
E2E="$HERE/.."
source "$E2E/common.sh"
source "$E2E/port-forward.sh"

# client.properties resolves the Auditor CA cert relative to scalardl-cleanup/.
cd "$E2E/../.."

props="$E2E/client.properties"

require_vars CLIENT RUNNER_TEMP RECORD_COUNT

"$E2E/run-cleanup.sh"

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
