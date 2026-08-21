#!/usr/bin/env bash
#
# Shared definitions for the E2E scripts. Source it:
#
#   source ci/e2e/common.sh
#
# It holds the cluster facts both scripts must agree on -- namespaces, the Auditor's TLS SAN and
# where its cert lives -- plus the kubectl, Job and Cosmos helpers they use.
#
# The Job helpers assume the deployed Jobs run the cleanup tool, which prints its machine-readable
# result as a single JSON line on stdout and everything else on stderr.

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

# Dump everything useful about a namespace's workloads, then fail. Everything goes to stderr so that
# the diagnostics still reach the log when this runs inside a command substitution -- where stdout is
# the caller's return value, and where `exit` only ends the subshell (the caller then aborts on the
# failed substitution because of `set -e`).
diag_and_die() {
  local ns="$1" msg="$2"
  {
    echo "::error::${msg}"
    echo "----- pods (${ns}) -----";            kubectl -n "$ns" get pods -o wide || true
    echo "----- events (${ns}) -----";          kubectl -n "$ns" get events --sort-by=.lastTimestamp || true
    echo "----- describe pods (${ns}) -----";   kubectl -n "$ns" describe pods || true
    echo "----- logs (${ns}) -----"
    for p in $(kubectl -n "$ns" get pods -o name 2>/dev/null || true); do
      echo "### $p"
      kubectl -n "$ns" logs "$p" --all-containers --prefix --tail=200 || true
    done
  } >&2
  exit 1
}

# Whether a Job carries the given condition ("Complete" or "Failed") with status True.
# Usage: job_has_condition <namespace> <job> <condition>
job_has_condition() {
  kubectl -n "$1" get "job/$2" \
    -o jsonpath="{.status.conditions[?(@.type==\"$3\")].status}" 2>/dev/null | grep -q True
}

# Poll a Job for complete/failed (kubectl wait --for=complete hangs on failure),
# printing diagnostics inline the moment it fails or times out.
wait_for_job() {
  local ns="$1" job="$2" timeout="${3:-300}" waited=0
  while true; do
    if job_has_condition "$ns" "$job" Complete; then
      echo "job/$job complete"; return 0
    fi
    if job_has_condition "$ns" "$job" Failed; then
      diag_and_die "$ns" "job/$job failed"
    fi
    if (( waited >= timeout )); then
      diag_and_die "$ns" "job/$job did not complete within ${timeout}s"
    fi
    sleep 5; waited=$((waited + 5))
  done
}

# Poll a Job until it either completes or fails, echoing which one happened. Unlike wait_for_job,
# a failure is a legitimate outcome here: the negative tests assert on it, and an interrupted run
# whose Job cannot retry ends up Failed by design.
# Usage: wait_for_job_terminal <namespace> <job> [timeout]
wait_for_job_terminal() {
  local ns="$1" job="$2" timeout="${3:-300}" waited=0
  while true; do
    if job_has_condition "$ns" "$job" Complete; then printf 'Complete\n'; return 0; fi
    if job_has_condition "$ns" "$job" Failed; then printf 'Failed\n'; return 0; fi
    if (( waited >= timeout )); then
      diag_and_die "$ns" "job/$job did not reach a terminal state within ${timeout}s"
    fi
    sleep 5; waited=$((waited + 5))
  done
}

# Echo the name of the most recently created Pod of a Job in the given phase, or nothing. Callers
# report the empty result themselves, so a kubectl failure must not abort them either (`|| true`).
# Usage: job_pod <namespace> <job> <phase>
job_pod() {
  kubectl -n "$1" get pod -l "job-name=$2" --field-selector="status.phase=$3" \
    --sort-by=.metadata.creationTimestamp -o jsonpath='{.items[*].metadata.name}' 2>/dev/null \
    | awk '{print $NF}' || true
}

# Echo the log of a Job's Pod in the given phase, or fail if there is no such Pod.
# Usage: read_job_log <namespace> <job> <phase>
read_job_log() {
  local ns="$1" job="$2" phase="$3" pod
  pod=$(job_pod "$ns" "$job" "$phase")
  [ -n "$pod" ] || { echo "::error::no $phase Pod found for job/$job in $ns" >&2; return 1; }
  kubectl -n "$ns" logs "$pod"
}

# Fail unless the log of a Job's Pod in the given phase contains the given text.
# Usage: assert_job_log_contains <namespace> <job> <phase> <text>
assert_job_log_contains() {
  local ns="$1" job="$2" phase="$3" text="$4" log
  log=$(read_job_log "$ns" "$job" "$phase")
  if ! printf '%s\n' "$log" | grep -qF "$text"; then
    echo "::error::job/$job did not log \"$text\""
    printf '%s\n' "$log" | tail -n 50
    return 1
  fi
  echo "job/$job logged \"$text\" as expected"
}

# Interrupt a running Job the moment it reports that it has started working, by force-deleting its
# Pod -- the E2E stand-in for the crash (OOM kill, node eviction, operator ^C) that a resumable
# command has to survive. The marker is a log line printed only after the command has persisted its
# initial checkpoint state, so a resumed run is guaranteed to find a checkpoint to resume from.
#
# Echoes what happened: "interrupted" if a Pod was killed, or "finished" if the Job reached a
# terminal state first (possible with the small E2E dataset, where the whole sweep can outrun the
# poll below; the caller's re-run then still exercises the resume path).
# Usage: interrupt_job <namespace> <job> <marker> [timeout]
interrupt_job() {
  local ns="$1" job="$2" marker="$3" timeout="${4:-300}" waited=0 pod
  while (( waited < timeout )); do
    if job_has_condition "$ns" "$job" Complete || job_has_condition "$ns" "$job" Failed; then
      printf 'finished\n'; return 0
    fi
    pod=$(kubectl -n "$ns" get pod -l "job-name=$job" --field-selector=status.phase=Running \
      -o jsonpath='{.items[*].metadata.name}' 2>/dev/null | awk '{print $1}' || true)
    if [ -n "$pod" ] && kubectl -n "$ns" logs "$pod" 2>/dev/null | grep -qF "$marker"; then
      echo "==> interrupting job/$job: force-deleting pod/$pod after it logged \"$marker\"" >&2
      kubectl -n "$ns" delete "pod/$pod" --grace-period=0 --force --wait=false >&2
      printf 'interrupted\n'; return 0
    fi
    sleep 1; waited=$((waited + 1))
  done
  diag_and_die "$ns" "job/$job neither logged \"$marker\" nor finished within ${timeout}s"
}

# Upsert an AD's credentials Secret. The Jobs pull it in with envFrom, so it has to exist before they
# run. `create --dry-run=client | apply` is the upsert: a plain `create` fails once it exists.
# Usage: upsert_credentials_secret <namespace> <cosmos-key>
upsert_credentials_secret() {
  kubectl -n "$1" create secret generic scalardl-cleanup-credentials \
    --from-literal=SCALAR_DB_PASSWORD="$2" \
    --dry-run=client -o yaml | kubectl apply -f -
}

# Apply a Job manifest, dropping any leftover of the same name first: Jobs are immutable, so a
# re-run of this script would otherwise fail on apply. As in manage-cluster.sh, give envsubst an
# explicit variable list so it substitutes ONLY these placeholders and never rewrites a literal
# $VAR that later lands in a manifest. The tokens are unset until Step 3 and expand to empty, but
# the finalize manifests never reference them.
# Usage: apply_job <namespace> <job> <manifest>
apply_job() {
  kubectl -n "$1" delete "job/$2" --ignore-not-found
  envsubst '${CLEANUP_VERSION} ${LEDGER_TOKEN} ${AUDITOR_TOKEN}' < "$3" | kubectl -n "$1" apply -f -
}

# Echo the tool's JSON output from the Pod of a completed Job in the given phase. The tool prints its
# JSON on stdout and its logs on stderr, which `kubectl logs` merges, so `jq -R 'fromjson?'` is what
# picks the JSON line out of the log4j lines around it.
# Usage: read_job_json <namespace> <job> <phase>
read_job_json() {
  local ns="$1" job="$2" phase="$3" json
  json=$(read_job_log "$ns" "$job" "$phase" \
    | jq -Rc 'fromjson? | select(type == "object")' | tail -n1)
  [ -n "$json" ] || { echo "::error::job/$job printed no JSON output" >&2; return 1; }
  printf '%s\n' "$json"
}

# Echo the JSON output of a Job that succeeded / failed.
# Usage: read_job_output_json <namespace> <job> | read_job_error_json <namespace> <job>
read_job_output_json() { read_job_json "$1" "$2" Succeeded; }
read_job_error_json() { read_job_json "$1" "$2" Failed; }

# Fail unless the given error JSON reports the expected status code and carries the expected
# structured error code (e.g. DL-TOOLS-1005) in its message.
# Usage: assert_error_json <json> <expected-status-code> <expected-error-code>
assert_error_json() {
  local json="$1" expected_status="$2" expected_code="$3" status message
  status=$(printf '%s' "$json" | jq -r '.status_code // empty')
  message=$(printf '%s' "$json" | jq -r '.error_message // empty')
  if [ "$status" != "$expected_status" ]; then
    echo "::error::expected status_code $expected_status but got '$status' ($json)"; return 1
  fi
  case "$message" in
    *"$expected_code"*) ;;
    *) echo "::error::expected error code $expected_code in the message but got '$message'"; return 1 ;;
  esac
  echo "rejected as expected: $message"
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
