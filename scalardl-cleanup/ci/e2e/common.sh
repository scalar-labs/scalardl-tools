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

# The PostgreSQL that manage-cluster.sh deploys into the Ledger's namespace, for the multi-storage
# scenarios. Exported because postgres.yaml and fixture-multistorage.properties refer to them. The
# password is a disposable test value for this ephemeral cluster only.
export POSTGRES_SERVICE="postgres"
export POSTGRES_PORT="5432"
# Local end of the port-forward the fixtures CLI, on the runner, reaches it through. Not 5432, which
# a PostgreSQL on the runner itself would hold.
export POSTGRES_LOCAL_PORT="15432"
export POSTGRES_USER="postgres"
export POSTGRES_PASSWORD="e2e-postgres-disposable"
export POSTGRES_DB="postgres"
# The namespace the fixture keeps its table in there. ScalarDB makes it a PostgreSQL schema.
export POSTGRES_NAMESPACE="e2e_pg"

# Which storage configuration a scenario runs the Ledger AD's commands with:
#   cosmos         the Ledger's Cosmos DB account only (manifests/ledger-ad/configmap.yaml)
#   multi-storage  that account plus the PostgreSQL above, where the fixture then keeps a second
#                  table (manifests/ledger-ad/configmap-multistorage.yaml)
# It holds for a whole scenario. The servers use Cosmos DB only either way.
: "${E2E_STORAGE:=cosmos}"
case "$E2E_STORAGE" in
  cosmos|multi-storage) ;;
  *) echo "ERROR: E2E_STORAGE must be cosmos or multi-storage, not '$E2E_STORAGE'" >&2; exit 1 ;;
esac
export E2E_STORAGE

# Abort unless every named environment variable is set and non-empty.
# Usage: require_vars <var>...
require_vars() {
  for v in "$@"; do
    if [[ -z "${!v:-}" ]]; then echo "ERROR: $v must be set" >&2; exit 1; fi
  done
}

# Dump everything useful about a namespace's workloads, then fail.
# Usage: diag_and_die <namespace> <message>
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

# Poll a Job until it reaches the awaited condition (kubectl wait --for=complete hangs on failure),
# printing diagnostics inline the moment it reaches the other one or times out.
# Usage: wait_for_job_condition <namespace> <job> <awaited: Complete|Failed> <timeout>
wait_for_job_condition() {
  local ns="$1" job="$2" awaited="$3" timeout="$4" waited=0 other
  if [ "$awaited" = Complete ]; then other=Failed; else other=Complete; fi
  while true; do
    if job_condition_true "$ns" "$job" "$awaited"; then
      echo "job/$job reached $awaited"; return 0
    fi
    if job_condition_true "$ns" "$job" "$other"; then
      diag_and_die "$ns" "job/$job reached $other but $awaited was expected"
    fi
    if (( waited >= timeout )); then
      diag_and_die "$ns" "job/$job reached neither $awaited nor $other within ${timeout}s"
    fi
    sleep 5; waited=$((waited + 5))
  done
}

# Usage: job_condition_true <namespace> <job> <condition>
job_condition_true() {
  kubectl -n "$1" get "job/$2" \
    -o jsonpath="{.status.conditions[?(@.type==\"$3\")].status}" 2>/dev/null | grep -q True
}

# Wait for a Job to succeed.
# Usage: wait_for_job <namespace> <job> [timeout]
wait_for_job() { wait_for_job_condition "$1" "$2" Complete "${3:-300}"; }

# Wait for a Job to fail.
# Usage: wait_for_job_failure <namespace> <job> [timeout]
wait_for_job_failure() { wait_for_job_condition "$1" "$2" Failed "${3:-300}"; }
