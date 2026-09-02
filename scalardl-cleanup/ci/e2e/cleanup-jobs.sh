#!/usr/bin/env bash
#
# Helpers for running the ScalarDL Cleanup commands as Kubernetes Jobs against the E2E cluster.
# Source it:
#
#   source ci/e2e/cleanup-jobs.sh
#
# Expects in the environment:
#   CLEANUP_VERSION  tag of the scalardl-cleanup image
#   COSMOSDB_SHELL   path to the Azure Cosmos DB Shell binary, used to count rows in Cosmos
#   RUNNER_TEMP      scratch directory for the rendered manifests
# and a kubectl context with the ledger-e2e / auditor-e2e namespaces deployed. It sets no shell
# options of its own; the caller is expected to run under `set -euo pipefail`, which some of these
# helpers rely on to surface a failure.
#
# The setup functions communicate through globals rather than arguments. Call them in this order,
# and read what they set:
#   init_manifests_workdir              -> work
#   load_ad_credentials                 -> ledger_uri / ledger_key / auditor_uri / auditor_key
#                                          (and ledger_props / auditor_props, their source files)
#   setup_ledger_ad / setup_auditor_ad  take no arguments; both of the above must be set by then

E2E_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$E2E_DIR/common.sh"

MANIFESTS="$E2E_DIR/../../manifests"

# Working copy of the manifests, one directory per administrative domain as in the source tree.
init_manifests_workdir() {
  work="$RUNNER_TEMP/manifests"
  rm -rf "$work"
  mkdir -p "$work"
  cp -R "$MANIFESTS/ledger-ad" "$MANIFESTS/auditor-ad" "$work/"
}

# Read a single property value from a properties file.
# Usage: read_prop_value <file> <key>
read_prop_value() { grep -m1 "^$2=" "$1" | cut -d= -f2-; }

# Each AD's Cosmos endpoint and key, taken from that AD's deployed server config. Reading them back
# from the cluster points the cleanup Jobs at exactly the account and credentials their server uses,
# and keeps these scripts free of secrets of their own.
load_ad_credentials() {
  ledger_props="$RUNNER_TEMP/ledger.properties"
  auditor_props="$RUNNER_TEMP/auditor.properties"

  kubectl -n "$LEDGER_NS"  get secret ledger-config  -o jsonpath='{.data.ledger\.properties}'  | base64 -d > "$ledger_props"
  kubectl -n "$AUDITOR_NS" get secret auditor-config -o jsonpath='{.data.auditor\.properties}' | base64 -d > "$auditor_props"
  ledger_uri=$(read_prop_value "$ledger_props" scalar.db.contact_points)
  ledger_key=$(read_prop_value "$ledger_props" scalar.db.password)
  auditor_uri=$(read_prop_value "$auditor_props" scalar.db.contact_points)
  auditor_key=$(read_prop_value "$auditor_props" scalar.db.password)
}

# Upsert an AD's credentials Secret. The Jobs pull it in with envFrom, so it has to exist before they
# run. `create --dry-run=client | apply` is the upsert: a plain `create` fails once it exists.
# Usage: upsert_credentials_secret <namespace> <cosmos-key>
upsert_credentials_secret() {
  kubectl -n "$1" create secret generic scalardl-cleanup-credentials \
    --from-literal=SCALAR_DB_PASSWORD="$2" \
    --dry-run=client -o yaml | kubectl apply -f -
}

# Prepare the Ledger AD for its Jobs.
setup_ledger_ad() {
  # Create the credentials Secret.
  upsert_credentials_secret "$LEDGER_NS" "$ledger_key"

  # Create the checkpoint volume.
  kubectl -n "$LEDGER_NS" apply -f "$work/ledger-ad/pvc.yaml"

  # Apply the ConfigMap, substituting contact_points first.
  sed "s|<cosmos-account-uri>|$ledger_uri|" \
    "$MANIFESTS/ledger-ad/configmap.yaml" > "$work/ledger-ad/configmap.yaml"
  kubectl -n "$LEDGER_NS" apply -f "$work/ledger-ad/configmap.yaml"
}

# Prepare the Auditor AD for its Jobs.
setup_auditor_ad() {
  # Create the credentials Secret.
  upsert_credentials_secret "$AUDITOR_NS" "$auditor_key"

  # Create the checkpoint volume.
  kubectl -n "$AUDITOR_NS" apply -f "$work/auditor-ad/pvc.yaml"

  # Apply the ConfigMap, substituting contact_points and the Auditor host first. The E2E Auditor
  # serves TLS, so uncomment the tls.* lines too and pin its cert SAN, which the host above is not.
  sed -E \
    -e "s|<cosmos-account-uri>|$auditor_uri|" \
    -e "s|<auditor-host>|auditor|" \
    -e "s|<auditor-cert-cn-or-san>|$AUDITOR_TLS_SAN|" \
    -e 's|^([[:space:]]*)#(scalar\.dl\.client\.auditor\.tls\.)|\1\2|' \
    "$MANIFESTS/auditor-ad/configmap.yaml" > "$work/auditor-ad/configmap.yaml"
  kubectl -n "$AUDITOR_NS" apply -f "$work/auditor-ad/configmap.yaml"

  # Create the cert Secret that completes the TLS setup: it holds the CA root cert that signed the
  # Auditor's server cert. manage-cluster.sh generated that self-signed cert at deploy time.
  kubectl -n "$AUDITOR_NS" create secret generic scalardl-cleanup-cert \
    --from-file=tls.crt="$CERTS_DIR/auditor.crt" \
    --dry-run=client -o yaml | kubectl apply -f -
}

# Apply a Job manifest, dropping any leftover of the same name first: Jobs are immutable, so a
# re-run would otherwise fail on apply. As in manage-cluster.sh, give envsubst an explicit variable
# list so it substitutes ONLY these placeholders and never rewrites a literal $VAR that later lands
# in a manifest. CLEANUP_VERSION must be exported, or the image tag renders empty; LEDGER_TOKEN and
# AUDITOR_TOKEN expand to empty when unset, which is fine for a manifest that does not use them.
# Usage: apply_job <namespace> <job> <manifest>
apply_job() {
  kubectl -n "$1" delete "job/$2" --ignore-not-found
  envsubst '${CLEANUP_VERSION} ${LEDGER_TOKEN} ${AUDITOR_TOKEN}' < "$3" | kubectl -n "$1" apply -f -
}

# Echo the tool's JSON output from the Job's Pod in the given phase. The tool prints its JSON on
# stdout and its logs on stderr, which `kubectl logs` merges, so `jq -R 'fromjson?'` is what picks the
# JSON line out of the log4j lines around it.
# Usage: read_job_json <namespace> <job> <pod-phase>
read_job_json() {
  local ns="$1" job="$2" phase="$3" pod json
  pod=$(kubectl -n "$ns" get pod -l "job-name=$job" \
    --field-selector=status.phase="$phase" -o jsonpath='{.items[0].metadata.name}')
  [ -n "$pod" ] || { echo "::error::no $phase Pod found for job/$job in $ns" >&2; return 1; }
  json=$(kubectl -n "$ns" logs "$pod" | jq -Rc 'fromjson? | select(type == "object")' | tail -n1)
  [ -n "$json" ] || { echo "::error::job/$job printed no JSON output" >&2; return 1; }
  printf '%s\n' "$json"
}

# Echo the JSON output of a completed Job.
# Usage: read_job_output_json <namespace> <job>
read_job_output_json() { read_job_json "$1" "$2" Succeeded; }

# Poll a Job for failed/complete, the mirror image of wait_for_job in common.sh: here completing is
# the unexpected outcome, so that is what triggers the diagnostics.
# Usage: wait_for_job_failure <namespace> <job> [timeout]
wait_for_job_failure() {
  local ns="$1" job="$2" timeout="${3:-300}" waited=0
  while true; do
    if kubectl -n "$ns" get "job/$job" -o jsonpath='{.status.conditions[?(@.type=="Failed")].status}' 2>/dev/null | grep -q True; then
      echo "job/$job failed, as expected"; return 0
    fi
    if kubectl -n "$ns" get "job/$job" -o jsonpath='{.status.conditions[?(@.type=="Complete")].status}' 2>/dev/null | grep -q True; then
      diag_and_die "$ns" "job/$job completed but was expected to fail"
    fi
    if (( waited >= timeout )); then
      diag_and_die "$ns" "job/$job neither failed nor completed within ${timeout}s"
    fi
    sleep 5; waited=$((waited + 5))
  done
}

# Run a Job that is expected to fail and echo the error JSON it printed, so the caller can assert on
# the status code and the error message. The manifest's backoffLimit applies as usual, so allow for
# the Job retrying a deterministic failure to exhaustion when choosing the timeout. The progress
# output of the two steps before the read goes to stderr, to keep it out of the caller's command
# substitution.
# Usage: run_job_expect_failure <namespace> <job> <manifest> [timeout]
run_job_expect_failure() {
  local ns="$1" job="$2" manifest="$3" timeout="${4:-600}"
  apply_job "$ns" "$job" "$manifest" >&2
  wait_for_job_failure "$ns" "$job" "$timeout" >&2
  read_job_json "$ns" "$job" Failed
}

# Empty an AD's checkpoint volume, so the next command starts a fresh run rather than resuming. The
# Jobs' Pods hold the PVC through the pvc-protection finalizer, so they go first;
# --cascade=foreground waits for the Pods rather than reaping them in the background. Nothing
# recreates the PVC here: setup_ledger_ad / setup_auditor_ad apply pvc.yaml before a Job runs.
# Usage: reset_checkpoint <namespace>
reset_checkpoint() {
  local ns="$1"
  kubectl -n "$ns" delete job -l app.kubernetes.io/name=scalardl-cleanup \
    --ignore-not-found --cascade=foreground --timeout=120s
  kubectl -n "$ns" delete pvc scalardl-cleanup-checkpoint --ignore-not-found --timeout=120s
}

# Extract a non-empty completion token from a finalize command's JSON output, or fail.
# Usage: extract_completion_token <command-name> <json>
extract_completion_token() {
  local name="$1" json="$2" token
  token=$(printf '%s' "$json" | jq -r '.output.completion_token // empty')
  [ -n "$token" ] || { echo "::error::$name emitted no completion token" >&2; return 1; }
  printf '%s\n' "$token"
}

# Count items in a container, authenticating with the given account endpoint and key.
# Usage: count_cosmos_items <endpoint> <key> <database> <container>
count_cosmos_items() {
  local endpoint="$1" account_key="$2" database="$3" container="$4" output count
  output=$("$COSMOSDB_SHELL" <<EOF
connect "AccountEndpoint=${endpoint};AccountKey=${account_key};"
cd ${database}
cd ${container}
query "SELECT * FROM c" | jq '.items | length'
exit
EOF
)
  # Extract the count integer from the shell output, and fail if none is found. `|| true` keeps a
  # no-match (grep exit 1, fatal under `set -o pipefail`) from aborting before the empty-check below
  # can report a useful error.
  count=$(printf '%s\n' "$output" | grep -oxE '[0-9]+' | tail -n1 || true)
  if [ -z "$count" ]; then
    echo "::error::could not parse a row count for ${database}/${container} from Cosmos DB Shell output" >&2
    printf '%s\n' "$output" >&2
    return 1
  fi
  printf '%s\n' "$count"
}
