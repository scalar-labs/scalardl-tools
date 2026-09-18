#!/usr/bin/env bash
#
# Populate the E2E cluster with the garbage the cleanup commands are meant to reclaim.
#
# Every mode records the ids it populated in $RUNNER_TEMP/populated-assets.json, which verify-assets
# reads back.
#
# Modes:
#   commit-objects             register the client identity, then commit RECORD_COUNT objects
#   strand-locks               scale the Ledger down and leave unreleased Auditor asset locks
#                              behind. It leaves the Ledger at 0 replicas; `manage-cluster.sh
#                              change-version` brings it back
#   commit-post-token-objects  commit RECORD_COUNT objects after the finalize commands took their
#                              completion tokens
#   verify-assets              run the object contracts against every asset the metadata lists,
#                              given the count to expect
#
# Required environment:
#   CLIENT        path to the ScalarDL HashStore CLI (the OLD version: the garbage is created by
#                 the old client)
#   RECORD_COUNT  records generated per category
#   RUNNER_TEMP   scratch directory holding the populated-assets metadata
#
# Usage:
#   ./populate.sh commit-objects | strand-locks | commit-post-token-objects
#   ./populate.sh verify-assets <expected-id-count>

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# LEDGER_NS / AUDITOR_NS and require_vars come from common.sh; pf_* from port-forward.sh.
source "$HERE/common.sh"
source "$HERE/port-forward.sh"

# client.properties resolves the Auditor CA cert relative to scalardl-cleanup/.
cd "$HERE/../.."

props="$HERE/client.properties"

# Mirrors LockOrderRecoveryHandler.LOCK_VALID_PERIOD_MILLIS in scalardl-enterprise.
LOCK_VALID_PERIOD_SECS=15

# Record a category's ids in the metadata file, so a scenario can tell what was populated.
# Usage: record_asset_ids <metadata-key> <id-prefix>
record_asset_ids() {
  local key="$1" prefix="$2" meta="$RUNNER_TEMP/populated-assets.json"
  [ -f "$meta" ] || jq -n '{entityId: "e2e-client"}' > "$meta"
  jq --argjson n "$RECORD_COUNT" --arg key "$key" --arg prefix "$prefix" \
    '.[$key] = [range(0; $n) | "\($prefix)-\(.)"]' "$meta" > "$meta.new"
  mv "$meta.new" "$meta"
}

# Commit RECORD_COUNT objects under the given id prefix.
# Usage: commit_objects <id-prefix> <metadata-key>
commit_objects() {
  local prefix="$1" key="$2"
  pf_reset
  pf_start_all
  "$CLIENT" bootstrap --properties "$props" # Idempotent
  # In auditor mode each put-object (object.Put = get+put) produces asset / request_proof /
  # coordinator.state / released asset_lock records.
  for i in $(seq 0 $((RECORD_COUNT - 1))); do
    id="$prefix-$i"
    hash=$(printf '%s' "$id" | sha256sum | cut -d' ' -f1)
    "$CLIENT" put-object --properties "$props" --object-id "$id" --hash "$hash"
  done
  record_asset_ids "$key" "$prefix"
}

# Leave unreleased Auditor asset locks behind, past their valid period so they can be finalized.
# Usage: strand_locks
strand_locks() {
  pf_reset
  "$HERE/manage-cluster.sh" stop-ledger
  pf_start "$AUDITOR_NS" svc/auditor 40051:40051 40052:40052
  pf_wait 40051 40052
  # Every op MUST fail (Ledger down): the Auditor is left holding the lock. Fail the job if
  # any unexpectedly succeeds. put-object leaves a WRITE lock on a fresh id; get-object leaves
  # a READ lock on an already-committed id.
  for i in $(seq 0 $((RECORD_COUNT - 1))); do
    id="e2e-stranded-$i"
    hash=$(printf '%s' "$id" | sha256sum | cut -d' ' -f1)
    if "$CLIENT" put-object --properties "$props" --object-id "$id" --hash "$hash"; then
      echo "::error::stranded put-object on $id unexpectedly succeeded (Ledger was supposed to be down)"; exit 1
    fi
  done
  for i in $(seq 0 $((RECORD_COUNT - 1))); do
    id="e2e-asset-$i"
    if "$CLIENT" get-object --properties "$props" --object-id "$id"; then
      echo "::error::stranded get-object on $id unexpectedly succeeded (Ledger was supposed to be down)"; exit 1
    fi
  done
  echo "Waiting $((LOCK_VALID_PERIOD_SECS + 1))s for the held locks to pass the ${LOCK_VALID_PERIOD_SECS}s valid period ..."
  sleep $((LOCK_VALID_PERIOD_SECS + 1))
  record_asset_ids strandedAssetIds e2e-stranded
}

# Check that put-object, get-object and validate-ledger still work on every asset after cleanup.
# put-object goes first because a stranded id has no committed version to read yet, and it reads the
# asset before writing anyway.
# Usage: verify_assets <expected-id-count>
verify_assets() {
  local expected="$1" meta="$RUNNER_TEMP/populated-assets.json" ids count id hash
  pf_reset
  pf_start_all
  # Read the ids up front: a process substitution's exit status is invisible to `set -e`, so piping
  # jq into the loop would run it zero times on a bad file and report success.
  [ -s "$meta" ] || { echo "::error::$meta is missing or empty"; exit 1; }
  ids=$(jq -r '[.[] | select(type == "array")] | flatten | .[]' "$meta") \
    || { echo "::error::could not read the asset ids from $meta"; exit 1; }
  count=$(printf '%s\n' "$ids" | grep -c . || true)
  [ "$count" -eq "$expected" ] \
    || { echo "::error::$meta lists $count asset ids (expected $expected)"; exit 1; }
  while IFS= read -r id; do
    hash=$(printf '%s' "$id" | sha256sum | cut -d' ' -f1)
    "$CLIENT" put-object --properties "$props" --object-id "$id" --hash "$hash" \
      || { echo "::error::put-object on $id failed after cleanup"; exit 1; }
    "$CLIENT" get-object --properties "$props" --object-id "$id" \
      || { echo "::error::get-object on $id failed after cleanup"; exit 1; }
    "$CLIENT" validate-ledger --properties "$props" --object-id "$id" \
      || { echo "::error::validate-ledger on $id failed after cleanup"; exit 1; }
  done <<< "$ids"
}

case "${1:-}" in
  commit-objects)
    require_vars CLIENT RECORD_COUNT RUNNER_TEMP
    commit_objects e2e-asset committedAssetIds
    ;;
  strand-locks)
    require_vars CLIENT RECORD_COUNT RUNNER_TEMP
    strand_locks
    ;;
  commit-post-token-objects)
    require_vars CLIENT RECORD_COUNT RUNNER_TEMP
    commit_objects e2e-post-token postTokenAssetIds
    ;;
  verify-assets)
    require_vars CLIENT RUNNER_TEMP
    verify_assets "${2:?usage: populate.sh verify-assets <expected-id-count>}"
    ;;
  *)
    echo "usage: populate.sh [commit-objects|strand-locks|commit-post-token-objects|verify-assets]" >&2
    exit 1
    ;;
esac
