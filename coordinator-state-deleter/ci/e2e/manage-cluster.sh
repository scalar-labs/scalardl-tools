#!/usr/bin/env bash
#
# Lifecycle helper for the E2E ScalarDL cluster on minikube against a real Cosmos DB.
# Driven by .github/workflows/e2e.yaml. CI-only: needs network access, ghcr
# credentials, and a Cosmos account.
#
# Modes:
#   deploy       load the ScalarDB schema, deploy Ledger + Auditor, wait until healthy
#   stop-ledger  scale the Ledger to 0 and wait for its pod to be deleted
#   upgrade      switch Ledger + Auditor to the SCALARDL_VERSION images, bring the Ledger
#                back up (it was scaled to 0 to strand locks), and wait until both are healthy
#   drop-schema  delete the ScalarDB schema (Cosmos cleanup)
#   clean        delete the k8s namespaces
#
# The cleanup tool is meant to reclaim garbage left behind by an older ScalarDL server while
# coordinating with the upgraded server. The E2E therefore deploys an old version, creates the
# garbage, then runs `upgrade` to the new version before running the tool:
#   SCALARDL_VERSION=<old> ... ./manage-cluster.sh deploy    # create the garbage
#   SCALARDL_VERSION=<new>     ./manage-cluster.sh upgrade   # roll servers to the new version
#
# Required environment (deploy and drop-schema only):
#   COSMOS_URI   Cosmos DB account URI
#   COSMOS_KEY   Cosmos DB primary key
#   CR_PAT       ghcr Personal Access Token with read:packages
#   GHCR_USER    ghcr username that owns CR_PAT
#
# Optional (default shown):
#   SCALARDL_VERSION=3.13.0   # image tag for ledger, auditor, and schema-loader
#
# Usage:
#   COSMOS_URI=... COSMOS_KEY=... CR_PAT=... GHCR_USER=... ./manage-cluster.sh deploy
#   SCALARDL_VERSION=<new> ./manage-cluster.sh upgrade
#   ./manage-cluster.sh stop-ledger | drop-schema | clean

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# K8s namespaces.
export LEDGER_NS="ledger-e2e"
export AUDITOR_NS="auditor-e2e"

SCALARDL_VERSION_EXPLICIT="${SCALARDL_VERSION:+yes}"   # "yes" if set and non-empty, else ""

# Container image names.
: "${SCALARDL_VERSION:=3.13.0}"
export LEDGER_IMAGE="ghcr.io/scalar-labs/scalardl-ledger:${SCALARDL_VERSION}"
export AUDITOR_IMAGE="ghcr.io/scalar-labs/scalardl-auditor:${SCALARDL_VERSION}"
export SL_IMAGE="ghcr.io/scalar-labs/scalardl-schema-loader:${SCALARDL_VERSION}"

# HMAC auth values.
export SERVERS_HMAC_SECRET="e2e-servers-hmac-secret-disposable-0123456789"
export HMAC_CIPHER_KEY="e2e-hmac-cipher-key-disposable-0123456789abcdef"

# Schema-loader arguments. schema-loader.yaml is parametrized for both create (deploy)
# and delete (drop-schema); these are the create defaults, overridden in drop-schema.
export SL_JOB_SUFFIX=""
export SL_LEDGER_ARGS='["--config", "/config/database.properties", "--coordinator"]'
export SL_AUDITOR_ARGS='["--config", "/config/database.properties"]'

# Give envsubst an explicit variable list so it substitutes ONLY these placeholders.
# Without a list, envsubst expands EVERY $VAR in the manifests, which would wrongly
# rewrite unrelated shell-style tokens (e.g. a literal $@ in a container command).
SUBST_VARS='${LEDGER_NS} ${AUDITOR_NS} ${LEDGER_IMAGE} ${AUDITOR_IMAGE} ${SL_IMAGE} '
SUBST_VARS+='${COSMOS_URI} ${COSMOS_KEY} ${SERVERS_HMAC_SECRET} ${HMAC_CIPHER_KEY} '
SUBST_VARS+='${SL_JOB_SUFFIX} ${SL_LEDGER_ARGS} ${SL_AUDITOR_ARGS}'

render() { envsubst "$SUBST_VARS" < "$HERE/$1"; }

# require_vars VAR...
# Abort unless every named environment variable is set and non-empty.
require_vars() {
  for v in "$@"; do
    if [[ -z "${!v:-}" ]]; then echo "ERROR: $v must be set" >&2; exit 1; fi
  done
}

clean() {
  echo "Deleting namespaces ${LEDGER_NS} and ${AUDITOR_NS} ..."
  kubectl delete namespace "$LEDGER_NS" "$AUDITOR_NS" --ignore-not-found
}

# Scale the Ledger to 0 and wait for its pod to disappear.
stop_ledger() {
  echo "==> scale down the Ledger"
  kubectl -n "$LEDGER_NS" scale deployment/scalardl-ledger --replicas=0
  # `kubectl wait --for=delete` errors with "no matching resources found" if the pod is already
  # gone — the Ledger was already scaled down, or the pod was deleted in the window between the
  # scale and the wait. Treat a failed wait as success unless a pod actually still remains.
  if ! kubectl -n "$LEDGER_NS" wait --for=delete pod -l app=scalardl-ledger --timeout=120s 2>/dev/null; then
    if [[ -n "$(kubectl -n "$LEDGER_NS" get pods -l app=scalardl-ledger -o name)" ]]; then
      echo "::error::Ledger pod did not terminate within 120s"
      exit 1
    fi
  fi
}

# Roll the Ledger and Auditor to the SCALARDL_VERSION images (via `set image`, reusing the existing
# config and pull secret) and wait until healthy. Also brings the Ledger back up, since it was
# scaled to 0 earlier to strand locks.
upgrade() {
  echo "==> upgrade Ledger and Auditor images to ${LEDGER_IMAGE} / ${AUDITOR_IMAGE}"
  kubectl -n "$LEDGER_NS"  set image deployment/scalardl-ledger  scalardl-ledger="$LEDGER_IMAGE"
  kubectl -n "$AUDITOR_NS" set image deployment/scalardl-auditor scalardl-auditor="$AUDITOR_IMAGE"
  kubectl -n "$LEDGER_NS"  scale deployment/scalardl-ledger --replicas=1

  echo "==> wait for rollouts to finish"
  if ! kubectl -n "$LEDGER_NS" rollout status deployment/scalardl-ledger --timeout=300s; then
    diag_and_die "$LEDGER_NS" "Ledger rollout to $LEDGER_IMAGE did not complete"
  fi
  if ! kubectl -n "$AUDITOR_NS" rollout status deployment/scalardl-auditor --timeout=300s; then
    diag_and_die "$AUDITOR_NS" "Auditor rollout to $AUDITOR_IMAGE did not complete"
  fi

  echo "==> gRPC health checks"
  if ! kubectl -n "$LEDGER_NS" exec deploy/scalardl-ledger -- \
      /usr/local/bin/grpc_health_probe -addr=:50051; then
    diag_and_die "$LEDGER_NS" "Ledger gRPC health check failed after upgrade to $LEDGER_IMAGE"
  fi
  if ! kubectl -n "$AUDITOR_NS" exec deploy/scalardl-auditor -- \
      /usr/local/bin/grpc_health_probe -addr=:40051; then
    diag_and_die "$AUDITOR_NS" "Auditor gRPC health check failed after upgrade to $AUDITOR_IMAGE"
  fi
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

# Wait for a Deployment to become available, with inline diagnostics on timeout.
wait_for_deploy() {
  local ns="$1" dep="$2" timeout="${3:-300}"
  if ! kubectl -n "$ns" wait --for=condition=available "deployment/$dep" --timeout="${timeout}s"; then
    diag_and_die "$ns" "deployment/$dep did not become available within ${timeout}s"
  fi
}

# Deploy schema + servers and block until both Deployments are available and pass a
# gRPC health check. Leaves the servers running.
deploy_all() {
  echo "==> create namespaces"
  kubectl create namespace "$LEDGER_NS" --dry-run=client -o yaml | kubectl apply -f -
  kubectl create namespace "$AUDITOR_NS" --dry-run=client -o yaml | kubectl apply -f -

  echo "==> create ghcr pull secret in both namespaces"
  for ns in "$LEDGER_NS" "$AUDITOR_NS"; do
    kubectl create secret docker-registry ghcr-secret \
      --docker-server=ghcr.io \
      --docker-username="$GHCR_USER" \
      --docker-password="$CR_PAT" \
      --namespace="$ns" \
      --dry-run=client -o yaml | kubectl apply -f -
  done

  echo "==> load schemas (ledger + coordinator, auditor)"
  # Jobs are immutable, so delete any leftovers first to keep re-runs idempotent.
  kubectl -n "$LEDGER_NS"  delete job schema-loader-ledger  --ignore-not-found || true
  kubectl -n "$AUDITOR_NS" delete job schema-loader-auditor --ignore-not-found || true
  render schema-loader.yaml | kubectl apply -f -
  wait_for_job "$LEDGER_NS"  schema-loader-ledger  300
  wait_for_job "$AUDITOR_NS" schema-loader-auditor 300

  echo "==> deploy ledger + auditor"
  render ledger.yaml  | kubectl apply -f -
  render auditor.yaml | kubectl apply -f -

  echo "==> wait for deployments to become available"
  wait_for_deploy "$LEDGER_NS"  scalardl-ledger  300
  wait_for_deploy "$AUDITOR_NS" scalardl-auditor 300

  echo "==> gRPC health checks"
  if ! kubectl -n "$LEDGER_NS" exec deploy/scalardl-ledger -- \
      /usr/local/bin/grpc_health_probe -addr=:50051; then
    diag_and_die "$LEDGER_NS" "Ledger gRPC health check failed"
  fi
  if ! kubectl -n "$AUDITOR_NS" exec deploy/scalardl-auditor -- \
      /usr/local/bin/grpc_health_probe -addr=:40051; then
    diag_and_die "$AUDITOR_NS" "Auditor gRPC health check failed"
  fi
}

case "${1:-}" in
  deploy)
    require_vars COSMOS_URI COSMOS_KEY CR_PAT GHCR_USER
    deploy_all
    echo
    echo "DEPLOY OK: servers Ready in $LEDGER_NS / $AUDITOR_NS."
    ;;
  stop-ledger)
    stop_ledger
    echo
    echo "STOP-LEDGER OK: $LEDGER_NS/scalardl-ledger scaled to 0."
    ;;
  upgrade)
    if [[ -z "$SCALARDL_VERSION_EXPLICIT" ]]; then
      echo "ERROR: SCALARDL_VERSION must be set explicitly for upgrade" >&2; exit 1
    fi
    upgrade
    echo
    echo "UPGRADE OK: $LEDGER_NS / $AUDITOR_NS rolled to ${SCALARDL_VERSION} and healthy."
    ;;
  drop-schema)
    require_vars COSMOS_URI COSMOS_KEY CR_PAT GHCR_USER
    # If neither namespace exists, there is nothing to drop.
    if ! kubectl get namespace "$LEDGER_NS" >/dev/null 2>&1 \
        && ! kubectl get namespace "$AUDITOR_NS" >/dev/null 2>&1; then
      echo "Namespaces ${LEDGER_NS}/${AUDITOR_NS} not found; nothing to drop."
      exit 0
    fi
    # Delete the ScalarDB schema so the shared Cosmos account stays clean between runs,
    # by re-rendering schema-loader.yaml with delete args + a distinct name suffix (a
    # completed create Job can't be re-applied under the same name).
    echo "==> delete ScalarDB schema (Cosmos cleanup)"
    export SL_JOB_SUFFIX="-delete"
    export SL_LEDGER_ARGS='["--config", "/config/database.properties", "-D", "--coordinator"]'
    export SL_AUDITOR_ARGS='["--config", "/config/database.properties", "-D"]'
    # Delete leftovers first (Jobs are immutable) so drop-schema is re-runnable.
    kubectl -n "$LEDGER_NS"  delete job schema-loader-ledger-delete  --ignore-not-found || true
    kubectl -n "$AUDITOR_NS" delete job schema-loader-auditor-delete --ignore-not-found || true
    render schema-loader.yaml | kubectl apply -f -
    wait_for_job "$LEDGER_NS"  schema-loader-ledger-delete  300
    wait_for_job "$AUDITOR_NS" schema-loader-auditor-delete 300
    echo
    echo "DROP-SCHEMA OK"
    ;;
  clean)
    clean
    ;;
  *)
    echo "usage: manage-cluster.sh [deploy|stop-ledger|upgrade|drop-schema|clean]" >&2
    exit 1
    ;;
esac
