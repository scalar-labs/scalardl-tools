#!/usr/bin/env bash
#
# Run the three ScalarDL Cleanup commands against the deployed E2E cluster the way an operator does:
#
#   Step 1 (Ledger AD)  credentials Secret -> checkpoint PVC -> ConfigMap -> finalize-ledger Job
#   Step 2 (Auditor AD) credentials Secret -> checkpoint PVC -> ConfigMap (+ TLS optional settings)
#                       -> finalize-auditor Job
#   Step 3 (Ledger AD)  cleanup-coordinator Job, with both completion tokens
#
# Expects in the environment:
#   CLEANUP_VERSION  tag of the scalardl-cleanup image
#   COSMOSDB_SHELL   path to the Azure Cosmos DB Shell binary, used to count rows in Cosmos
#   RUNNER_TEMP      scratch directory for the rendered ConfigMaps
#   RECORD_COUNT     number of records populated per category
# and a kubectl context with the ledger-e2e / auditor-e2e namespaces deployed.

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/common.sh"

require_vars CLEANUP_VERSION COSMOSDB_SHELL RUNNER_TEMP RECORD_COUNT

# The Job manifests take the image tag as ${CLEANUP_VERSION}; the namespace is not in them and is
# passed to kubectl instead. LEDGER_NS / AUDITOR_NS / AUDITOR_TLS_SAN come from common.sh exported.
export CLEANUP_VERSION

MANIFESTS="$HERE/../../manifests"

# Working copy of the manifests, one directory per administrative domain as in the source tree.
work="$RUNNER_TEMP/manifests"
rm -rf "$work"
mkdir -p "$work"
cp -R "$MANIFESTS/ledger-ad" "$MANIFESTS/auditor-ad" "$work/"

ledger_props="$RUNNER_TEMP/ledger.properties"
auditor_props="$RUNNER_TEMP/auditor.properties"

# Read a single property value from a properties file.
# Usage: read_prop_value <file> <key>
read_prop_value() { grep -m1 "^$2=" "$1" | cut -d= -f2-; }

# Each AD's Cosmos endpoint and key, taken from that AD's deployed server config. Reading them back
# from the cluster points the cleanup Jobs at exactly the account and credentials their server uses,
# and keeps this script free of secrets of its own.
kubectl -n "$LEDGER_NS"  get secret ledger-config  -o jsonpath='{.data.ledger\.properties}'  | base64 -d > "$ledger_props"
kubectl -n "$AUDITOR_NS" get secret auditor-config -o jsonpath='{.data.auditor\.properties}' | base64 -d > "$auditor_props"
ledger_uri=$(read_prop_value "$ledger_props" scalar.db.contact_points)
ledger_key=$(read_prop_value "$ledger_props" scalar.db.password)
auditor_uri=$(read_prop_value "$auditor_props" scalar.db.contact_points)
auditor_key=$(read_prop_value "$auditor_props" scalar.db.password)

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

# Echo the tool's JSON output from the succeeded Pod of a completed Job. The tool prints its JSON on
# stdout and its logs on stderr, which `kubectl logs` merges, so `jq -R 'fromjson?'` is what picks the
# JSON line out of the log4j lines around it.
# Usage: read_job_output_json <namespace> <job>
read_job_output_json() {
  local ns="$1" job="$2" pod json
  pod=$(kubectl -n "$ns" get pod -l "job-name=$job" \
    --field-selector=status.phase=Succeeded -o jsonpath='{.items[0].metadata.name}')
  [ -n "$pod" ] || { echo "::error::no succeeded Pod found for job/$job in $ns" >&2; return 1; }
  json=$(kubectl -n "$ns" logs "$pod" | jq -Rc 'fromjson? | select(type == "object")' | tail -n1)
  [ -n "$json" ] || { echo "::error::job/$job printed no JSON output" >&2; return 1; }
  printf '%s\n' "$json"
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

# --- Step 1 (Ledger AD): finalize-ledger -------------------------------------------------------
echo "== Step 1: finalize-ledger =="

# (a) Create the credentials Secret.
upsert_credentials_secret "$LEDGER_NS" "$ledger_key"

# (b) Create the checkpoint volume.
kubectl -n "$LEDGER_NS" apply -f "$work/ledger-ad/pvc.yaml"

# (c) Apply the ConfigMap, substituting contact_points first.
sed "s|<cosmos-account-uri>|$ledger_uri|" \
  "$MANIFESTS/ledger-ad/configmap.yaml" > "$work/ledger-ad/configmap.yaml"
kubectl -n "$LEDGER_NS" apply -f "$work/ledger-ad/configmap.yaml"

# (d) Run the Job.
apply_job "$LEDGER_NS" scalardl-finalize-ledger "$work/ledger-ad/finalize-ledger.yaml"
wait_for_job "$LEDGER_NS" scalardl-finalize-ledger 600
ledger_out=$(read_job_output_json "$LEDGER_NS" scalardl-finalize-ledger)
echo "finalize-ledger output: $ledger_out"
token_l=$(extract_completion_token finalize-ledger "$ledger_out")

# --- Step 2 (Auditor AD): finalize-auditor -----------------------------------------------------
echo "== Step 2: finalize-auditor =="

# (a) Create the credentials Secret.
upsert_credentials_secret "$AUDITOR_NS" "$auditor_key"

# (b) Create the checkpoint volume.
kubectl -n "$AUDITOR_NS" apply -f "$work/auditor-ad/pvc.yaml"

# (c) Apply the ConfigMap, substituting contact_points and the Auditor host first. The E2E Auditor
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

# (d) Run the Job. Recovering a stranded lock can wait out the lock's valid period, hence the longer
# timeout.
rp_before=$(count_cosmos_items "$auditor_uri" "$auditor_key" auditor request_proof)
apply_job "$AUDITOR_NS" scalardl-finalize-auditor "$work/auditor-ad/finalize-auditor.yaml"
wait_for_job "$AUDITOR_NS" scalardl-finalize-auditor 900
auditor_out=$(read_job_output_json "$AUDITOR_NS" scalardl-finalize-auditor)
echo "finalize-auditor output: $auditor_out"
token_a=$(extract_completion_token finalize-auditor "$auditor_out")
rp_after=$(count_cosmos_items "$auditor_uri" "$auditor_key" auditor request_proof)
echo "request_proof rows: before=$rp_before after=$rp_after"
[ "$rp_after" -eq 0 ] || { echo "::error::finalize-auditor left $rp_after request_proof rows (expected 0)"; exit 1; }

# --- Step 3 (Ledger AD): cleanup-coordinator ---------------------------------------------------
echo "== Step 3: cleanup-coordinator =="

# The Job manifest takes both tokens as ${LEDGER_TOKEN} / ${AUDITOR_TOKEN}.
export LEDGER_TOKEN="$token_l" AUDITOR_TOKEN="$token_a"

cs_before=$(count_cosmos_items "$ledger_uri" "$ledger_key" coordinator state)
apply_job "$LEDGER_NS" scalardl-cleanup-coordinator "$work/ledger-ad/cleanup-coordinator.yaml"
wait_for_job "$LEDGER_NS" scalardl-cleanup-coordinator 600
coord_out=$(read_job_output_json "$LEDGER_NS" scalardl-cleanup-coordinator)
echo "cleanup-coordinator output: $coord_out"
[ "$(printf '%s' "$coord_out" | jq -r '.status_code')" = "OK" ] \
  || { echo "::error::cleanup-coordinator did not report OK"; exit 1; }

cs_after=$(count_cosmos_items "$ledger_uri" "$ledger_key" coordinator state)
# Only the RECORD_COUNT committed put transactions are settled before the deletable-before boundary,
# so exactly those are removed. finalize-auditor's recovery aborts each stranded lock's nonce,
# writing coordinator.state rows after the boundary, which survive and are not counted here.
echo "coordinator.state rows: before=$cs_before after=$cs_after (expected deleted=$RECORD_COUNT)"
[ "$((cs_before - cs_after))" -eq "$RECORD_COUNT" ] \
  || { echo "::error::cleanup-coordinator deleted $((cs_before - cs_after)) coordinator.state rows (expected $RECORD_COUNT)"; exit 1; }
[ "$cs_after" -eq "$((2 * RECORD_COUNT))" ] \
  || { echo "::error::coordinator.state has $cs_after rows after cleanup (expected $((2 * RECORD_COUNT)))"; exit 1; }

echo "All three cleanup commands succeeded."
