#!/usr/bin/env bash
#
# Populate the E2E cluster with the garbage the cleanup commands are meant to reclaim.
#
# Modes, in the order .github/workflows/e2e.yaml runs them:
#   commit-objects  register the client identity, then commit RECORD_COUNT objects
#   strand-locks    scale the Ledger down and leave unreleased Auditor asset locks behind. It
#                   leaves the Ledger at 0 replicas; `manage-cluster.sh upgrade` brings it back
#   write-metadata  record what was populated, for the verification step to read back
#
# Required environment:
#   CLIENT        path to the ScalarDL HashStore CLI (the OLD version: the garbage is created by
#                 the old client)
#   RECORD_COUNT  records generated per category
#   RUNNER_TEMP   scratch directory (metadata mode only)
#
# Usage:
#   ./populate.sh commit-objects | strand-locks | write-metadata

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

commit_objects() {
  pf_reset
  pf_start "$LEDGER_NS"  svc/ledger  50051:50051 50052:50052
  pf_start "$AUDITOR_NS" svc/auditor 40051:40051 40052:40052
  pf_wait 50051 50052 40051 40052
  # Register the client identity + the generic object contracts on both servers.
  "$CLIENT" bootstrap --properties "$props"
  # Commit RECORD_COUNT objects. In auditor mode each put-object (object.Put = get+put)
  # produces asset / request_proof / coordinator.state / released asset_lock records.
  for i in $(seq 0 $((RECORD_COUNT - 1))); do
    id="e2e-asset-$i"
    hash=$(printf '%s' "$id" | sha256sum | cut -d' ' -f1)
    "$CLIENT" put-object --properties "$props" --object-id "$id" --hash "$hash"
  done
}

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
}

# Record what was populated. The verify step reads this back (single source of truth for the
# asset ids), and it is uploaded as an artifact for inspection. strandedReadAssetIds are the
# committed ids that also received a stranded READ lock (== committedAssetIds here).
write_metadata() {
  jq -n --argjson n "$RECORD_COUNT" '{
    entityId: "e2e-client",
    committedAssetIds: [range(0; $n) | "e2e-asset-\(.)"],
    strandedAssetIds: [range(0; $n) | "e2e-stranded-\(.)"],
    strandedReadAssetIds: [range(0; $n) | "e2e-asset-\(.)"]
  }' > "$RUNNER_TEMP/populated-assets.json"
}

case "${1:-}" in
  commit-objects)
    require_vars CLIENT RECORD_COUNT
    commit_objects
    ;;
  strand-locks)
    require_vars CLIENT RECORD_COUNT
    strand_locks
    ;;
  write-metadata)
    require_vars RECORD_COUNT RUNNER_TEMP
    write_metadata
    ;;
  *)
    echo "usage: populate.sh [commit-objects|strand-locks|write-metadata]" >&2
    exit 1
    ;;
esac
