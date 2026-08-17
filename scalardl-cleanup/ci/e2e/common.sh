#!/usr/bin/env bash
#
# Shared definitions for the E2E scripts. Source it:
#
#   source ci/e2e/common.sh
#
# It holds the cluster facts both scripts must agree on -- namespaces, the Auditor's TLS SAN and
# where its cert lives -- plus the kubectl helpers they both use.

# K8s namespaces. Exported because the manifests refer to them as ${LEDGER_NS} / ${AUDITOR_NS}.
export LEDGER_NS="ledger-e2e"
export AUDITOR_NS="auditor-e2e"

# CN/SAN of the disposable Auditor server cert, and the directory manage-cluster.sh generates it
# into. A client that reaches the Auditor under a different name has to pin this as the TLS authority.
export AUDITOR_TLS_SAN="auditor.e2e.scalar-labs.com"
CERTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/certs"

# require_vars VAR...
# Abort unless every named environment variable is set and non-empty.
require_vars() {
  for v in "$@"; do
    if [[ -z "${!v:-}" ]]; then echo "ERROR: $v must be set" >&2; exit 1; fi
  done
}

# Dump everything useful about a namespace's workloads, then fail.
diag_and_die() {
  local ns="$1" msg="$2"
  echo "::error::${msg}"
  echo "----- pods (${ns}) -----";            kubectl -n "$ns" get pods -o wide || true
  echo "----- events (${ns}) -----";          kubectl -n "$ns" get events --sort-by=.lastTimestamp || true
  echo "----- describe pods (${ns}) -----";   kubectl -n "$ns" describe pods || true
  echo "----- logs (${ns}) -----"
  for p in $(kubectl -n "$ns" get pods -o name 2>/dev/null || true); do
    echo "### $p"
    kubectl -n "$ns" logs "$p" --all-containers --prefix --tail=200 || true
  done
  exit 1
}

# Poll a Job for complete/failed (kubectl wait --for=complete hangs on failure),
# printing diagnostics inline the moment it fails or times out.
wait_for_job() {
  local ns="$1" job="$2" timeout="${3:-300}" waited=0
  while true; do
    if kubectl -n "$ns" get "job/$job" -o jsonpath='{.status.conditions[?(@.type=="Complete")].status}' 2>/dev/null | grep -q True; then
      echo "job/$job complete"; return 0
    fi
    if kubectl -n "$ns" get "job/$job" -o jsonpath='{.status.conditions[?(@.type=="Failed")].status}' 2>/dev/null | grep -q True; then
      diag_and_die "$ns" "job/$job failed"
    fi
    if (( waited >= timeout )); then
      diag_and_die "$ns" "job/$job did not complete within ${timeout}s"
    fi
    sleep 5; waited=$((waited + 5))
  done
}
