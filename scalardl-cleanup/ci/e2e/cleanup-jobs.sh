#!/usr/bin/env bash
#
# Helpers for running the ScalarDL Cleanup commands as Kubernetes Jobs against the E2E cluster.
# Source it:
#
#   source ci/e2e/cleanup-jobs.sh
#
# Required environment:
#   CLEANUP_VERSION  tag of the scalardl-cleanup image
#   COSMOSDB_SHELL   path to the Azure Cosmos DB Shell binary, needed only by the counting helpers,
#                    which check for it themselves
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
# Usage: init_manifests_workdir
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
# Usage: load_ad_credentials
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

# Append $extra_cleanup_properties, if a scenario set it, to a rendered ConfigMap. The properties
# block is the last thing in both ConfigMaps, so appending at its indent is enough.
# Usage: append_extra_properties <rendered-configmap>
append_extra_properties() {
  [ -n "${extra_cleanup_properties:-}" ] || return 0
  # A key added after the block would leave valid YAML whose settings never reach the tool.
  if [ -n "$(tail -n1 "$1" | sed -n '/^    [^ ]/p')" ]; then
    printf '%s\n' "$extra_cleanup_properties" | sed 's/^/    /' >> "$1"
  else
    echo "::error::$1 does not end inside its properties block; cannot append settings" >&2
    return 1
  fi
}

# Prepare the Ledger AD for its Jobs.
# Usage: setup_ledger_ad
setup_ledger_ad() {
  # Create the credentials Secret.
  upsert_credentials_secret "$LEDGER_NS" "$ledger_key"

  # Create the checkpoint volume.
  kubectl -n "$LEDGER_NS" apply -f "$work/ledger-ad/pvc.yaml"

  # Apply the ConfigMap, substituting contact_points first.
  sed "s|<cosmos-account-uri>|$ledger_uri|" \
    "$MANIFESTS/ledger-ad/configmap.yaml" > "$work/ledger-ad/configmap.yaml"
  append_extra_properties "$work/ledger-ad/configmap.yaml"
  kubectl -n "$LEDGER_NS" apply -f "$work/ledger-ad/configmap.yaml"
}

# Prepare the Auditor AD for its Jobs.
# Usage: setup_auditor_ad
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
  append_extra_properties "$work/auditor-ad/configmap.yaml"
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
  # A Job that retried has several Pods in the same phase, and they all carry the same outcome, so
  # sort to pick the same one every time rather than whichever the API server lists first.
  pod=$(kubectl -n "$ns" get pod -l "job-name=$job" --field-selector=status.phase="$phase" \
    --sort-by=.metadata.creationTimestamp -o jsonpath='{.items[0].metadata.name}')
  [ -n "$pod" ] || { echo "::error::no $phase Pod found for job/$job in $ns" >&2; return 1; }
  json=$(kubectl -n "$ns" logs "$pod" | jq -Rc 'fromjson? | select(type == "object")' | tail -n1)
  [ -n "$json" ] || { echo "::error::job/$job printed no JSON output" >&2; return 1; }
  printf '%s\n' "$json"
}

# Echo the JSON output of a completed Job.
# Usage: read_job_output_json <namespace> <job>
read_job_output_json() { read_job_json "$1" "$2" Succeeded; }

# Run a Job that is expected to fail and echo the error JSON it printed. The manifest's backoffLimit
# applies, so the failure is retried to exhaustion, as an operator would see. Progress output goes
# to stderr, out of the caller's command substitution.
# Usage: run_job_expect_failure <namespace> <job> <manifest> [timeout]
run_job_expect_failure() {
  local ns="$1" job="$2" manifest="$3" timeout="${4:-600}"
  apply_job "$ns" "$job" "$manifest" >&2
  wait_for_job_failure "$ns" "$job" "$timeout" >&2
  read_job_json "$ns" "$job" Failed
}

# Empty an AD's checkpoint volume so the next command starts a fresh run. The Jobs' Pods hold the
# PVC, so they go first and are waited for; setup_ledger_ad / setup_auditor_ad recreate it.
# Usage: reset_checkpoint <namespace>
reset_checkpoint() {
  local ns="$1"
  kubectl -n "$ns" delete job -l app.kubernetes.io/name=scalardl-cleanup \
    --ignore-not-found --cascade=foreground --timeout=120s
  kubectl -n "$ns" delete pvc scalardl-cleanup-checkpoint --ignore-not-found --timeout=120s
}

# Run a Job, kill the Pod once it logs the caller's marker, and let the Job's own backoffLimit start
# the Pod that resumes: the checkpoint PVC outlives the Pod. Writes the killed Pod's log to
# $RUNNER_TEMP/<job>-interrupted.log and the resuming Pod's to <job>-resumed.log.
# Usage: interrupt_and_resume <namespace> <job> <manifest> <started-marker> [timeout]
interrupt_and_resume() {
  local ns="$1" job="$2" manifest="$3" marker="$4" timeout="${5:-900}"
  local first="" resuming="" before="" ok_pod pod names n deadline
  apply_job "$ns" "$job" "$manifest"

  # SECONDS, not a sleep counter: the kubectl calls in these loops cost more than the sleeps do.
  deadline=$((SECONDS + 300))
  # Capture the log as soon as the marker appears: once the Pod is gone, so is `kubectl logs`.
  while true; do
    # The newest Pod, not the first: if the Job retried before the marker appeared, the oldest one
    # is dead and its log would never produce it.
    pod=$(kubectl -n "$ns" get pod -l "job-name=$job" --sort-by=.metadata.creationTimestamp \
      -o jsonpath='{.items[-1:].metadata.name}' 2>/dev/null || true)
    if [ -n "$pod" ] && kubectl -n "$ns" logs "$pod" 2>/dev/null \
        > "$RUNNER_TEMP/$job-interrupted.log" \
        && grep -qF "$marker" "$RUNNER_TEMP/$job-interrupted.log"; then
      first="$pod"; break
    fi
    if job_condition_true "$ns" "$job" Complete; then
      diag_and_die "$ns" "job/$job finished before it could be interrupted; raise the record count so the scan lasts longer"
    fi
    (( SECONDS < deadline )) || diag_and_die "$ns" "job/$job never logged '$marker'"
    sleep 1
  done

  # Snapshot the Pods that exist now: the one that resumes is the name that is not among them.
  before=$(kubectl -n "$ns" get pod -l "job-name=$job" -o jsonpath='{.items[*].metadata.name}')

  # --force skips the graceful delete so the replacement starts promptly. Safe here because the
  # checkpoint PVC is minikube's hostPath: a real CSI driver could leave the volume attached and
  # block the next Pod from mounting it.
  echo "interrupting job/$job by deleting pod/$first"
  kubectl -n "$ns" delete "pod/$first" --grace-period=0 --force

  # Wait for the replacement. A completed Job will never make one, which means the interruption
  # missed -- fail rather than assert against a run that was never interrupted.
  deadline=$((SECONDS + 300))
  while [ -z "$resuming" ]; do
    if job_condition_true "$ns" "$job" Complete; then
      diag_and_die "$ns" "job/$job completed before the interruption landed; raise the record count so the scan lasts longer"
    fi
    names=$(kubectl -n "$ns" get pod -l "job-name=$job" -o jsonpath='{.items[*].metadata.name}')
    for n in $names; do
      # Take the first name missing from the snapshot.
      case " $before " in *" $n "*) ;; *) resuming="$n"; break ;; esac
    done
    [ -n "$resuming" ] && break
    (( SECONDS < deadline )) || diag_and_die "$ns" "job/$job did not start a Pod to resume with"
    sleep 2
  done
  echo "job/$job resumed in pod/$resuming"

  wait_for_job "$ns" "$job" "$timeout"
  ok_pod=$(kubectl -n "$ns" get pod -l "job-name=$job" \
    --field-selector=status.phase=Succeeded -o jsonpath='{.items[0].metadata.name}')
  [ -n "$ok_pod" ] || diag_and_die "$ns" "no succeeded Pod found for job/$job in $ns"
  [ "$ok_pod" = "$resuming" ] || diag_and_die "$ns" "job/$job resumed in pod/$resuming but succeeded in pod/$ok_pod"
  kubectl -n "$ns" logs "$ok_pod" > "$RUNNER_TEMP/$job-resumed.log"
}

# Extract a non-empty completion token from a finalize command's JSON output, or fail.
# Usage: extract_completion_token <command-name> <json>
extract_completion_token() {
  local name="$1" json="$2" token
  token=$(printf '%s' "$json" | jq -r '.output.completion_token // empty')
  [ -n "$token" ] || { echo "::error::$name emitted no completion token" >&2; return 1; }
  printf '%s\n' "$token"
}

# Count the asset locks still held: finalize-auditor recovers every one it finds, so zero is what
# says it left none behind. lock_type comes from AuditorInternalValues.
# Usage: count_cosmos_held_locks <endpoint> <key> <database>
count_cosmos_held_locks() {
  count_cosmos_records_by_query "$1" "$2" "$3" asset_lock \
    'SELECT VALUE COUNT(1) FROM c WHERE c.values.lock_type IN (2, 3)'
}

# Count the records left mid-transaction: such a record still needs its Coordinator record, so zero
# means cleanup-coordinator orphaned none. tx_state and its values are ScalarDB internals.
# Usage: count_cosmos_unsettled_records <endpoint> <key> <database> <container>
count_cosmos_unsettled_records() {
  count_cosmos_records_by_query "$1" "$2" "$3" "$4" \
    'SELECT VALUE COUNT(1) FROM c WHERE c.values.tx_state IN (1, 2)'
}

# Count items in a container, authenticating with the given account endpoint and key.
# Usage: count_cosmos_records <endpoint> <key> <database> <container>
count_cosmos_records() {
  count_cosmos_records_by_query "$1" "$2" "$3" "$4" 'SELECT VALUE COUNT(1) FROM c'
}

# Run a query that returns a single number, and echo it.
# Usage: count_cosmos_records_by_query <endpoint> <key> <database> <container> <query>
count_cosmos_records_by_query() {
  local endpoint="$1" account_key="$2" database="$3" container="$4" query="$5" output count
  require_vars COSMOSDB_SHELL
  output=$("$COSMOSDB_SHELL" <<EOF
connect "AccountEndpoint=${endpoint};AccountKey=${account_key};"
cd ${database}
cd ${container}
query "${query}" | jq '.items[0]'
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
