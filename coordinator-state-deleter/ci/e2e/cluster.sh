#!/usr/bin/env bash
#
# Lifecycle helper for the E2E ScalarDL cluster on minikube (against a real Cosmos
# DB). Used by .github/workflows/e2e-verify.yaml and runnable locally to bring up,
# switch versions, clean, or drop the schema. Needs network access, ghcr
# credentials, and a Cosmos account, so run it on a host (not the Claude sandbox).
#
# Modes:
#   deploy       deploy schemas + servers and wait until Ready (no port-forward)
#   redeploy     re-apply servers with the current *_VERSION (e.g. 3.13 -> 3.14)
#   drop-schema  delete the ScalarDB schema (Cosmos cleanup between runs)
#   clean        delete the run's k8s namespaces
#   smoke        deploy + health + port-forward reachability check (local sanity)
#
# Required environment (all modes except clean):
#   COSMOS_URI   Cosmos DB account URI
#   COSMOS_KEY   Cosmos DB primary key
#   CR_PAT       ghcr Personal Access Token with read:packages
#   GHCR_USER    ghcr username that owns CR_PAT
#
# Optional (defaults shown):
#   LEDGER_VERSION=3.13.0
#   AUDITOR_VERSION=3.13.0
#   SCHEMA_LOADER_VERSION=3.13.0
#   RUN_ID=local                 # namespace suffix, e.g. a CI run id
#
# Usage:
#   COSMOS_URI=... COSMOS_KEY=... CR_PAT=... GHCR_USER=... ./cluster.sh smoke
#   ./cluster.sh deploy | redeploy | drop-schema | clean

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

: "${RUN_ID:=local}"
export LEDGER_NS="ledger-e2e-${RUN_ID}"
export AUDITOR_NS="auditor-e2e-${RUN_ID}"

: "${LEDGER_VERSION:=3.13.0}"
: "${AUDITOR_VERSION:=3.13.0}"
: "${SCHEMA_LOADER_VERSION:=3.13.0}"
export LEDGER_IMAGE="ghcr.io/scalar-labs/scalardl-ledger:${LEDGER_VERSION}"
export AUDITOR_IMAGE="ghcr.io/scalar-labs/scalardl-auditor:${AUDITOR_VERSION}"
export SCHEMA_LOADER_IMAGE="ghcr.io/scalar-labs/scalardl-schema-loader:${SCHEMA_LOADER_VERSION}"

# Only these placeholders are substituted; anything else (e.g. $@ inside PEMs) is
# left untouched.
SUBST_VARS='${LEDGER_NS} ${AUDITOR_NS} ${LEDGER_IMAGE} ${AUDITOR_IMAGE} ${SCHEMA_LOADER_IMAGE} ${COSMOS_URI} ${COSMOS_KEY}'

render() { envsubst "$SUBST_VARS" < "$HERE/$1"; }

clean() {
  echo "Deleting namespaces ${LEDGER_NS} and ${AUDITOR_NS} ..."
  kubectl delete namespace "$LEDGER_NS" "$AUDITOR_NS" --ignore-not-found --wait=false
}

if [[ "${1:-}" == "clean" ]]; then
  clean
  exit 0
fi

# Dump everything useful about a namespace's workloads, then fail.
diag_and_die() {
  local ns="$1" msg="$2"
  echo "::error::${msg}"
  echo "----- pods (${ns}) -----";           kubectl -n "$ns" get pods -o wide || true
  echo "----- events (${ns}) -----";          kubectl -n "$ns" get events --sort-by=.lastTimestamp || true
  echo "----- describe pods (${ns}) -----";   kubectl -n "$ns" describe pods || true
  echo "----- logs (${ns}) -----"
  for p in $(kubectl -n "$ns" get pods -o name 2>/dev/null); do
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

# Wait for a Deployment to become Available, with inline diagnostics on timeout.
wait_for_deploy() {
  local ns="$1" dep="$2" timeout="${3:-300}"
  if ! kubectl -n "$ns" wait --for=condition=available "deployment/$dep" --timeout="${timeout}s"; then
    diag_and_die "$ns" "deployment/$dep did not become available within ${timeout}s"
  fi
}

# Background kubectl port-forwards; killed on exit so we never leak tunnels.
PF_PIDS=()
kill_port_forwards() {
  for pid in "${PF_PIDS[@]:-}"; do
    [[ -n "$pid" ]] && kill "$pid" 2>/dev/null || true
  done
}
trap kill_port_forwards EXIT

# TCP connect check (bash builtin, no extra tooling on the host).
check_tcp() { timeout 3 bash -c "exec 3<>/dev/tcp/127.0.0.1/$1" 2>/dev/null; }

# Start a port-forward and block until the local port actually accepts a
# connection, so callers can rely on the tunnel being live.
start_port_forward() {
  local ns="$1" target="$2"; shift 2   # remaining args: local:remote pairs
  kubectl -n "$ns" port-forward "$target" "$@" >/dev/null 2>&1 &
  PF_PIDS+=("$!")
  local first_local="${1%%:*}" waited=0
  until check_tcp "$first_local"; do
    if (( waited >= 30 )); then
      echo "::error::port-forward to $ns/$target ($*) never became reachable"; exit 1
    fi
    sleep 1; waited=$((waited + 1))
  done
}

require_vars() {
  for v in COSMOS_URI COSMOS_KEY CR_PAT GHCR_USER; do
    if [[ -z "${!v:-}" ]]; then echo "ERROR: $v must be set" >&2; exit 1; fi
  done
}

# Deploy schemas + servers and block until both Deployments are Available and
# pass a gRPC health check. Leaves the servers running (no teardown).
deploy_all() {
  echo "==> minikube start"
  minikube status >/dev/null 2>&1 || minikube start --cpus=4 --memory=8192

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
  render schema-loader.yaml | kubectl apply -f -
  wait_for_job "$LEDGER_NS"  schema-loader-ledger  300
  wait_for_job "$AUDITOR_NS" schema-loader-auditor 300

  echo "==> deploy ledger + auditor"
  render ledger.yaml  | kubectl apply -f -
  render auditor.yaml | kubectl apply -f -

  echo "==> wait for deployments to become available"
  wait_for_deploy "$LEDGER_NS"  scalardl-ledger  300
  wait_for_deploy "$AUDITOR_NS" scalardl-auditor 300

  echo "==> gRPC health checks (via grpc_health_probe baked into the images)"
  kubectl -n "$LEDGER_NS" exec deploy/scalardl-ledger -- \
    /usr/local/bin/grpc_health_probe -addr=:50051
  kubectl -n "$AUDITOR_NS" exec deploy/scalardl-auditor -- \
    /usr/local/bin/grpc_health_probe -addr=:40051

  echo "==> confirm auditor is not crashlooping (i.e. it reached the ledger)"
  kubectl -n "$AUDITOR_NS" get pods -o wide
  kubectl -n "$AUDITOR_NS" logs deploy/scalardl-auditor --tail=30 || true
}

require_vars

case "${1:-smoke}" in
  deploy)
    # Deploy and wait until Ready, then leave the servers running for a caller
    # (e.g. the gradle populate/verify step, which does its own port-forward) to
    # connect to. No port-forward, no teardown.
    deploy_all
    echo
    echo "DEPLOY OK: servers Ready in $LEDGER_NS / $AUDITOR_NS."
    ;;
  redeploy)
    # Re-apply only the servers with the current *_IMAGE (e.g. to switch 3.13.0 ->
    # 3.14.0-SNAPSHOT). Skips schema-loader Jobs (immutable once created) and reuses
    # the existing namespaces/secrets/Cosmos data.
    echo "==> re-apply ledger + auditor (image switch to ${LEDGER_IMAGE} / ${AUDITOR_IMAGE})"
    render ledger.yaml | kubectl apply -f -
    render auditor.yaml | kubectl apply -f -
    # Wait for the rolling update to FULLY complete, not just for min-availability.
    # 'kubectl wait --for=condition=available' returns as soon as the surged new pod
    # is Ready while the OLD pod is still terminating; a port-forward started next can
    # bind to that dying pod and fail mid-RPC with "container not running". 'rollout
    # status' blocks until the old ReplicaSet is scaled to 0 (only the new pod remains).
    kubectl -n "$LEDGER_NS" rollout status deployment/scalardl-ledger --timeout=300s
    kubectl -n "$AUDITOR_NS" rollout status deployment/scalardl-auditor --timeout=300s
    echo "==> gRPC health checks"
    kubectl -n "$LEDGER_NS" exec deploy/scalardl-ledger -- \
      /usr/local/bin/grpc_health_probe -addr=:50051
    kubectl -n "$AUDITOR_NS" exec deploy/scalardl-auditor -- \
      /usr/local/bin/grpc_health_probe -addr=:40051
    echo
    echo "REDEPLOY OK: servers now running ${LEDGER_IMAGE} / ${AUDITOR_IMAGE}."
    ;;
  smoke)
    deploy_all
    # Mirror how the gradle E2E process reaches the servers: kubectl port-forward
    # from the host. Ledger gRPC 50051 + privileged 50052; Auditor gRPC 40051
    # (svc/auditor) + privileged 40052 (svc/auditor).
    echo "==> port-forward servers to host and verify reachability"
    start_port_forward "$LEDGER_NS"  svc/ledger             50051:50051 50052:50052
    start_port_forward "$AUDITOR_NS" svc/auditor            40051:40051
    start_port_forward "$AUDITOR_NS" svc/auditor 40052:40052
    for port in 50051 50052 40051 40052; do
      if check_tcp "$port"; then
        echo "  localhost:$port reachable"
      else
        echo "::error::localhost:$port not reachable through port-forward"; exit 1
      fi
    done
    echo
    echo "SMOKE OK: both servers Ready and reachable via port-forward. Tear down with:  ./cluster.sh clean"
    ;;
  drop-schema)
    # Delete the ScalarDB schema so the shared Cosmos account stays clean between
    # runs. Reuses the existing schema-loader-config ConfigMap + ghcr-secret.
    echo "==> delete ScalarDB schema (Cosmos cleanup)"
    render schema-loader-delete.yaml | kubectl apply -f -
    wait_for_job "$LEDGER_NS"  schema-loader-delete-ledger  300
    wait_for_job "$AUDITOR_NS" schema-loader-delete-auditor 300
    echo
    echo "DROP-SCHEMA OK"
    ;;
  *)
    echo "usage: cluster.sh [smoke|deploy|redeploy|drop-schema|clean]" >&2
    exit 1
    ;;
esac
