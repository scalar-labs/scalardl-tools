#!/usr/bin/env bash
#
# Lifecycle helper for the E2E ScalarDL cluster on minikube against a real Cosmos DB.
# Driven by .github/workflows/e2e.yaml. CI-only: needs network access, ghcr
# credentials, and a Cosmos account.
#
# Modes:
#   deploy          load the ScalarDB schema, deploy Ledger + Auditor, wait until healthy
#   stop-ledger     scale the Ledger to 0 and wait for its pod to be deleted
#   change-version  switch Ledger + Auditor to the SCALARDL_VERSION images, bring the Ledger back
#                   up (it may have been scaled to 0 to strand locks), and wait until both are
#                   healthy. Works in both directions, so the fixture can roll back to the old one
#   drop-schema     delete the ScalarDB schema (Cosmos cleanup)
#   reset-schema    replace the schema with an empty one, and restart the servers so they see it
#   clean           delete the k8s namespaces
#
# The cleanup tool is meant to reclaim garbage left behind by an older ScalarDL server while
# coordinating with the upgraded server. The E2E therefore deploys an old version, creates the
# garbage, then runs `change-version` to the new version before running the tool:
#   SCALARDL_VERSION=<old> ... ./manage-cluster.sh deploy          # create the garbage
#   SCALARDL_VERSION=<new>     ./manage-cluster.sh change-version  # roll to the new version
#
# Required environment:
#   COSMOS_URI   Cosmos DB account URI                        (schema modes and deploy)
#   COSMOS_KEY   Cosmos DB primary key                        (schema modes and deploy)
#   CR_PAT       ghcr Personal Access Token with read:packages (deploy only: it creates the
#   GHCR_USER    ghcr username that owns CR_PAT                 ghcr-secret the Jobs pull with)
#
# Optional (default shown):
#   SCALARDL_VERSION=3.13.0   # image tag for ledger, auditor, and schema-loader
#
# Usage:
#   COSMOS_URI=... COSMOS_KEY=... CR_PAT=... GHCR_USER=... ./manage-cluster.sh deploy
#   SCALARDL_VERSION=<new> ./manage-cluster.sh change-version
#   ./manage-cluster.sh stop-ledger | drop-schema | reset-schema | clean

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# Namespaces, the Auditor TLS SAN, CERTS_DIR, and the kubectl helpers (require_vars,
# wait_for_job, diag_and_die) live here so every script in ci/e2e sees the same values.
source "$HERE/common.sh"

SCALARDL_VERSION_EXPLICIT="${SCALARDL_VERSION:+yes}"   # "yes" if set and non-empty, else ""

# Container image names.
: "${SCALARDL_VERSION:=3.13.0}"
export LEDGER_IMAGE="ghcr.io/scalar-labs/scalardl-ledger:${SCALARDL_VERSION}"
export AUDITOR_IMAGE="ghcr.io/scalar-labs/scalardl-auditor:${SCALARDL_VERSION}"
export SL_IMAGE="ghcr.io/scalar-labs/scalardl-schema-loader:${SCALARDL_VERSION}"

# HMAC auth values.
export SERVERS_HMAC_SECRET="e2e-servers-hmac-secret-disposable-0123456789"
export HMAC_CIPHER_KEY="e2e-hmac-cipher-key-disposable-0123456789abcdef"

# Give envsubst an explicit variable list so it substitutes ONLY these placeholders.
# Without a list, envsubst expands EVERY $VAR in the manifests, which would wrongly
# rewrite unrelated shell-style tokens (e.g. a literal $@ in a container command).
SUBST_VARS='${LEDGER_NS} ${AUDITOR_NS} ${LEDGER_IMAGE} ${AUDITOR_IMAGE} ${SL_IMAGE} '
SUBST_VARS+='${COSMOS_URI} ${COSMOS_KEY} ${SERVERS_HMAC_SECRET} ${HMAC_CIPHER_KEY} '
SUBST_VARS+='${SL_JOB_SUFFIX} ${SL_LEDGER_ARGS} ${SL_AUDITOR_ARGS}'

# Substitute the placeholders above into one of the manifests next to this script.
# Usage: render <manifest-filename>
render() { envsubst "$SUBST_VARS" < "$HERE/$1"; }

# Generate a disposable self-signed cert/key for the Auditor server TLS. Reused if it already exists,
# so re-running deploy does not rotate the cert out from under a running Auditor pod (`clean` drops it
# to force a fresh one). umask 077 keeps the private key private on a dev box.
# Usage: gen_certs
gen_certs() {
  mkdir -p "$CERTS_DIR"
  if [[ -s "$CERTS_DIR/auditor.crt" && -s "$CERTS_DIR/auditor-key.pem" ]]; then
    echo "==> reusing existing Auditor TLS cert (CN/SAN=${AUDITOR_TLS_SAN})"
    return
  fi
  echo "==> generate self-signed Auditor TLS cert (CN/SAN=${AUDITOR_TLS_SAN})"
  ( umask 077
    openssl req -x509 -newkey rsa:2048 -nodes \
      -keyout "$CERTS_DIR/auditor-key.pem" -out "$CERTS_DIR/auditor.crt" \
      -days 3650 -subj "/CN=${AUDITOR_TLS_SAN}" \
      -addext "subjectAltName=DNS:${AUDITOR_TLS_SAN}" )
}

# Delete the namespaces, and the disposable TLS cert with them.
# Usage: clean
clean() {
  echo "Deleting namespaces ${LEDGER_NS} and ${AUDITOR_NS} ..."
  kubectl delete namespace "$LEDGER_NS" "$AUDITOR_NS" --ignore-not-found
  # Drop the disposable TLS cert so the next deploy generates a fresh one (see gen_certs).
  rm -rf "$CERTS_DIR"
}

# Scale the Ledger to 0 and wait for its pod to disappear.
# Usage: stop_ledger
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
# config and pull secret) and wait until healthy. Also brings the Ledger back up, since populating
# stranded locks scales it to 0.
# Usage: change_version
change_version() {
  echo "==> set Ledger and Auditor images to ${LEDGER_IMAGE} / ${AUDITOR_IMAGE}"
  kubectl -n "$LEDGER_NS"  set image deployment/scalardl-ledger  scalardl-ledger="$LEDGER_IMAGE"
  kubectl -n "$AUDITOR_NS" set image deployment/scalardl-auditor scalardl-auditor="$AUDITOR_IMAGE"
  kubectl -n "$LEDGER_NS"  scale deployment/scalardl-ledger --replicas=1

  wait_for_rollouts " after switching to $SCALARDL_VERSION"
}

# Wait for both Deployments to finish rolling, then health-check them: condition=available stays
# true through a restart, and a tcpSocket probe cannot tell listening from serving.
# Usage: wait_for_rollouts [message-suffix]
wait_for_rollouts() {
  local suffix="${1:-}"
  echo "==> wait for rollouts to finish"
  if ! kubectl -n "$LEDGER_NS" rollout status deployment/scalardl-ledger --timeout=300s; then
    diag_and_die "$LEDGER_NS" "Ledger rollout did not complete${suffix}"
  fi
  if ! kubectl -n "$AUDITOR_NS" rollout status deployment/scalardl-auditor --timeout=300s; then
    diag_and_die "$AUDITOR_NS" "Auditor rollout did not complete${suffix}"
  fi
  grpc_health_checks "$suffix"
}

# Health-check the Ledger (plaintext) and Auditor (TLS, verified against the SAN), aborting with
# diagnostics on failure. The suffix names what was rolled, e.g. " after switching to <version>".
# Usage: grpc_health_checks [message-suffix]
grpc_health_checks() {
  local suffix="${1:-}"
  echo "==> gRPC health checks"
  if ! kubectl -n "$LEDGER_NS" exec deploy/scalardl-ledger -- \
      /usr/local/bin/grpc_health_probe -addr=:50051; then
    diag_and_die "$LEDGER_NS" "Ledger gRPC health check failed${suffix}"
  fi
  if ! kubectl -n "$AUDITOR_NS" exec deploy/scalardl-auditor -- \
      /usr/local/bin/grpc_health_probe -addr=:40051 \
      -tls -tls-ca-cert=/etc/scalardl-tls/tls.crt -tls-server-name="$AUDITOR_TLS_SAN"; then
    diag_and_die "$AUDITOR_NS" "Auditor gRPC health check failed${suffix}"
  fi
}

# Wait for a Deployment to become available, with inline diagnostics on timeout.
# Usage: wait_for_deploy <namespace> <deployment> [timeout]
wait_for_deploy() {
  local ns="$1" dep="$2" timeout="${3:-300}"
  if ! kubectl -n "$ns" wait --for=condition=available "deployment/$dep" --timeout="${timeout}s"; then
    diag_and_die "$ns" "deployment/$dep did not become available within ${timeout}s"
  fi
}

# Run the schema-loader Jobs. Jobs are immutable, so leftovers go first and create and delete get
# distinct name suffixes.
# Usage: run_schema_loader <job-suffix> <ledger-args> <auditor-args>
run_schema_loader() {
  export SL_JOB_SUFFIX="$1" SL_LEDGER_ARGS="$2" SL_AUDITOR_ARGS="$3"
  kubectl -n "$LEDGER_NS"  delete job "schema-loader-ledger$1"  --ignore-not-found
  kubectl -n "$AUDITOR_NS" delete job "schema-loader-auditor$1" --ignore-not-found
  render schema-loader.yaml | kubectl apply -f -
  wait_for_job "$LEDGER_NS"  "schema-loader-ledger$1"  300
  wait_for_job "$AUDITOR_NS" "schema-loader-auditor$1" 300
}

# Create the ScalarDB schema (ledger + coordinator, auditor).
# Usage: load_schema
load_schema() {
  echo "==> load schemas (ledger + coordinator, auditor)"
  run_schema_loader "" \
    '["--config", "/config/database.properties", "--coordinator"]' \
    '["--config", "/config/database.properties"]'
}

# Delete the ScalarDB schema.
# Usage: drop_schema
drop_schema() {
  echo "==> delete ScalarDB schema (Cosmos cleanup)"
  run_schema_loader "-delete" \
    '["--config", "/config/database.properties", "-D", "--coordinator"]' \
    '["--config", "/config/database.properties", "-D"]'
}

# Replace the schema with an empty one, then restart the servers so they drop their cached table
# metadata. The health check that follows needs the Ledger at 1 replica.
# Usage: reset_schema
reset_schema() {
  drop_schema
  load_schema
  echo "==> restart the servers so they read the recreated schema"
  kubectl -n "$LEDGER_NS"  rollout restart deployment/scalardl-ledger
  kubectl -n "$AUDITOR_NS" rollout restart deployment/scalardl-auditor
  wait_for_rollouts " after the schema reset"
}

# Deploy schema + servers and block until both Deployments are available and pass a
# gRPC health check. Leaves the servers running.
# Usage: deploy_all
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

  load_schema

  echo "==> create Auditor TLS cert + secret"
  gen_certs
  kubectl -n "$AUDITOR_NS" create secret generic auditor-tls \
    --from-file=tls.crt="$CERTS_DIR/auditor.crt" \
    --from-file=tls.key="$CERTS_DIR/auditor-key.pem" \
    --dry-run=client -o yaml | kubectl apply -f -

  echo "==> deploy ledger + auditor"
  render ledger.yaml  | kubectl apply -f -
  render auditor.yaml | kubectl apply -f -

  echo "==> wait for deployments to become available"
  wait_for_deploy "$LEDGER_NS"  scalardl-ledger  300
  wait_for_deploy "$AUDITOR_NS" scalardl-auditor 300

  grpc_health_checks
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
  change-version)
    if [[ -z "$SCALARDL_VERSION_EXPLICIT" ]]; then
      echo "ERROR: SCALARDL_VERSION must be set explicitly for change-version" >&2; exit 1
    fi
    change_version
    echo
    echo "CHANGE-VERSION OK: $LEDGER_NS / $AUDITOR_NS rolled to ${SCALARDL_VERSION} and healthy."
    ;;
  drop-schema)
    require_vars COSMOS_URI COSMOS_KEY
    # If neither namespace exists, there is nothing to drop.
    if ! kubectl get namespace "$LEDGER_NS" >/dev/null 2>&1 \
        && ! kubectl get namespace "$AUDITOR_NS" >/dev/null 2>&1; then
      echo "Namespaces ${LEDGER_NS}/${AUDITOR_NS} not found; nothing to drop."
      exit 0
    fi
    drop_schema
    echo
    echo "DROP-SCHEMA OK"
    ;;
  reset-schema)
    require_vars COSMOS_URI COSMOS_KEY
    reset_schema
    echo
    echo "RESET-SCHEMA OK: the schema is empty."
    ;;
  clean)
    clean
    ;;
  *)
    echo "usage: manage-cluster.sh [deploy|stop-ledger|change-version|drop-schema|reset-schema|clean]" >&2
    exit 1
    ;;
esac
