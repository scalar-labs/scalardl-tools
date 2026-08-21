#!/usr/bin/env bash
#
# Run the ScalarDL Cleanup commands against the deployed E2E cluster the way an operator does:
#
#   Step 1 (Ledger AD)  credentials Secret -> checkpoint PVC -> ConfigMap -> finalize-ledger Job
#   Step 2 (Auditor AD) credentials Secret -> checkpoint PVC -> ConfigMap (+ TLS optional settings)
#                       -> finalize-auditor Job
#   Step 3 (Ledger AD)  cleanup-coordinator Job, with both completion tokens
#
# One phase per invocation, so .github/workflows/e2e.yaml can interleave the server upgrade and
# fresh client traffic between them:
#
#   reject-old-auditor          finalize-auditor against the not-yet-upgraded Auditor must fail with
#                               an error telling the operator to upgrade, because the privileged
#                               RecoverAssetLock RPC does not exist there. Runs before the upgrade,
#                               on a throwaway checkpoint volume of its own.
#   finalize-ledger             Step 1. Interrupted midway and resumed by the Job's own retry, then
#                               re-run once more to prove a re-run changes nothing.
#   interrupt-finalize-auditor  Step 2, first attempt only: killed midway with retries disabled, so
#                               that the workflow can run new client traffic before the command is
#                               resumed.
#   finalize-auditor            Step 2, resumed. Emits the Auditor token and must delete exactly the
#                               request_proof records that predate its guarantee timestamp, leaving
#                               every record the new traffic created in place.
#   cleanup-coordinator         Step 3. Mixed-up tokens are rejected first, then the real run is
#                               interrupted, resumed and re-run.
#
# Each phase hands what the next one needs (completion tokens, row counts) to a file under
# RUNNER_TEMP.
#
# Expects in the environment:
#   CLEANUP_VERSION  tag of the scalardl-cleanup image
#   COSMOSDB_SHELL   path to the Azure Cosmos DB Shell binary, used to count rows in Cosmos
#   RUNNER_TEMP      scratch directory for the rendered manifests and the phase handover files
#   RECORD_COUNT     number of records populated per category
# and a kubectl context with the ledger-e2e / auditor-e2e namespaces deployed.
#
# Usage:
#   ./run-cleanup.sh <phase>

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/common.sh"

require_vars CLEANUP_VERSION COSMOSDB_SHELL RUNNER_TEMP RECORD_COUNT

# The Job manifests take the image tag as ${CLEANUP_VERSION}; the namespace is not in them and is
# passed to kubectl instead. LEDGER_NS / AUDITOR_NS / AUDITOR_TLS_SAN come from common.sh exported.
export CLEANUP_VERSION

MANIFESTS="$HERE/../../manifests"

# Job names, as defined in the manifests.
LEDGER_JOB="scalardl-finalize-ledger"
AUDITOR_JOB="scalardl-finalize-auditor"
COORDINATOR_JOB="scalardl-cleanup-coordinator"

# Names for the two negative tests, which get resources of their own so that a deliberate failure
# never interferes with the real run's Job or checkpoint.
OLD_SERVER_CHECK_JOB="scalardl-finalize-auditor-old-server-check"
OLD_SERVER_CHECK_PVC="scalardl-cleanup-checkpoint-old-server-check"
MIXED_TOKENS_JOB="scalardl-cleanup-coordinator-mixed-tokens"

# Log line every command prints once it has persisted its initial checkpoint state, and the line a
# run prints when it picks that checkpoint up again.
LEDGER_START_MARKER="Starting a new run at"
AUDITOR_START_MARKER="Starting a new run at"
COORDINATOR_START_MARKER="Starting a new run."
RESUME_MARKER="Found existing checkpoint data"

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

# Hand a value to a later phase, and read it back. A missing file means the phases ran out of order.
# Usage: save_handover <name> <value> | load_handover <name>
save_handover() { printf '%s\n' "$2" > "$RUNNER_TEMP/handover-$1"; }
load_handover() {
  local file="$RUNNER_TEMP/handover-$1"
  [ -s "$file" ] || { echo "::error::handover file $file is missing; run the earlier phases first" >&2; return 1; }
  cat "$file"
}

# Extract a non-empty completion token from a finalize command's JSON output, or fail.
# Usage: extract_completion_token <command-name> <json>
extract_completion_token() {
  local name="$1" json="$2" token
  token=$(printf '%s' "$json" | jq -r '.output.completion_token // empty')
  [ -n "$token" ] || { echo "::error::$name emitted no completion token" >&2; return 1; }
  printf '%s\n' "$token"
}

# Echo a Job manifest with the Job's retries disabled. A negative test reads the error output of the
# very first attempt, and the manifests' backoffLimit would re-run the command three more times
# before the Job reports Failed. It also keeps an interrupted Job down until it is applied again.
# Usage: render_without_retry <manifest>
render_without_retry() { sed 's/^\( *\)backoffLimit: [0-9]*$/\1backoffLimit: 0/' "$1"; }

# Prepare the Ledger AD for its Jobs: credentials Secret, checkpoint volume, ConfigMap. Idempotent,
# so every Ledger-side phase can call it without caring which ones ran before.
prepare_ledger_ad() {
  upsert_credentials_secret "$LEDGER_NS" "$ledger_key"
  kubectl -n "$LEDGER_NS" apply -f "$work/ledger-ad/pvc.yaml"
  # Apply the ConfigMap, substituting contact_points first.
  sed "s|<cosmos-account-uri>|$ledger_uri|" \
    "$MANIFESTS/ledger-ad/configmap.yaml" > "$work/ledger-ad/configmap.yaml"
  kubectl -n "$LEDGER_NS" apply -f "$work/ledger-ad/configmap.yaml"
}

# Prepare the Auditor AD for its Jobs: credentials Secret, checkpoint volume, ConfigMap, cert Secret.
# Idempotent. An alternative PVC manifest can be passed for a run that must not share the checkpoint.
# Usage: prepare_auditor_ad [pvc-manifest]
prepare_auditor_ad() {
  upsert_credentials_secret "$AUDITOR_NS" "$auditor_key"
  kubectl -n "$AUDITOR_NS" apply -f "${1:-$work/auditor-ad/pvc.yaml}"

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

# Apply a Job, interrupt it midway, and let the Job's own retry resume it from the checkpoint. Fails
# unless the attempt that finished the work resumed instead of starting over.
# Usage: run_interrupted_and_resumed <namespace> <job> <manifest> <start-marker> [timeout]
run_interrupted_and_resumed() {
  local ns="$1" job="$2" manifest="$3" marker="$4" timeout="${5:-600}" outcome
  apply_job "$ns" "$job" "$manifest"
  outcome=$(interrupt_job "$ns" "$job" "$marker" "$timeout")
  wait_for_job "$ns" "$job" "$timeout"
  if [ "$outcome" = interrupted ]; then
    assert_job_log_contains "$ns" "$job" Succeeded "$RESUME_MARKER"
  else
    echo "NOTE: job/$job finished before it could be interrupted; the re-run below still covers" \
      "the resume path, and the assertions on the final state are the same either way."
  fi
}

# Re-run a Job that already did its work, against the same checkpoint volume: the command must find
# the checkpoint, leave the data alone and report the same result. Echoes the re-run's JSON output.
# Usage: rerun_job <namespace> <job> <manifest> [timeout]
rerun_job() {
  local ns="$1" job="$2" manifest="$3" timeout="${4:-600}"
  apply_job "$ns" "$job" "$manifest" >&2
  wait_for_job "$ns" "$job" "$timeout" >&2
  assert_job_log_contains "$ns" "$job" Succeeded "$RESUME_MARKER" >&2
  read_job_output_json "$ns" "$job"
}

# --- Phase: finalize-auditor against a not-yet-upgraded Auditor ---------------------------------
# The old server has no RecoverAssetLock RPC, so the command cannot finalize a single lock. It must
# say so in a way that points at the fix (upgrade the Auditor) instead of surfacing the raw gRPC
# "UNIMPLEMENTED: Method not found" failure.
phase_reject_old_auditor() {
  echo "== Negative: finalize-auditor against the pre-upgrade Auditor =="

  # Give this run a checkpoint volume and a Job name of its own: a checkpoint left behind here would
  # pin the real Step 2 to a guarantee timestamp captured before the upgrade.
  sed "s|scalardl-cleanup-checkpoint|$OLD_SERVER_CHECK_PVC|" \
    "$MANIFESTS/auditor-ad/pvc.yaml" > "$work/auditor-ad/pvc-old-server-check.yaml"
  prepare_auditor_ad "$work/auditor-ad/pvc-old-server-check.yaml"

  render_without_retry "$MANIFESTS/auditor-ad/finalize-auditor.yaml" \
    | sed -e "s|scalardl-cleanup-checkpoint|$OLD_SERVER_CHECK_PVC|" \
          -e "s|^\( *\)name: $AUDITOR_JOB\$|\1name: $OLD_SERVER_CHECK_JOB|" \
    > "$work/auditor-ad/finalize-auditor-old-server-check.yaml"

  apply_job "$AUDITOR_NS" "$OLD_SERVER_CHECK_JOB" \
    "$work/auditor-ad/finalize-auditor-old-server-check.yaml"
  local result output message
  result=$(wait_for_job_terminal "$AUDITOR_NS" "$OLD_SERVER_CHECK_JOB" 600)
  [ "$result" = Failed ] || {
    echo "::error::finalize-auditor was expected to fail against the pre-upgrade Auditor but ended as $result"
    exit 1
  }

  output=$(read_job_error_json "$AUDITOR_NS" "$OLD_SERVER_CHECK_JOB")
  # DL-TOOLS-1014 is RECOVER_ASSET_LOCK_UNSUPPORTED: a USER_ERROR, not an opaque internal failure.
  assert_error_json "$output" USER_ERROR DL-TOOLS-1014
  message=$(printf '%s' "$output" | jq -r '.error_message')
  printf '%s' "$message" | grep -qi 'upgrade the auditor' || {
    echo "::error::the error message does not tell the operator to upgrade the Auditor: $message"
    exit 1
  }

  # Drop the throwaway Job and volume so nothing of this run outlives the phase.
  kubectl -n "$AUDITOR_NS" delete "job/$OLD_SERVER_CHECK_JOB" --ignore-not-found
  # --wait=false: the PVC keeps its protection finalizer until the Job's Pod is fully gone, and
  # nothing after this phase refers to this claim.
  kubectl -n "$AUDITOR_NS" delete "pvc/$OLD_SERVER_CHECK_PVC" --ignore-not-found --wait=false

  echo "finalize-auditor rejected the pre-upgrade Auditor with an actionable error."
}

# --- Phase: Step 1 (Ledger AD): finalize-ledger --------------------------------------------------
phase_finalize_ledger() {
  echo "== Step 1: finalize-ledger =="
  prepare_ledger_ad

  local manifest="$work/ledger-ad/finalize-ledger.yaml" output token rerun rerun_token
  run_interrupted_and_resumed "$LEDGER_NS" "$LEDGER_JOB" "$manifest" "$LEDGER_START_MARKER" 600
  output=$(read_job_output_json "$LEDGER_NS" "$LEDGER_JOB")
  echo "finalize-ledger output: $output"
  token=$(extract_completion_token finalize-ledger "$output")

  # The interrupted-then-resumed run has to land on the same completion token a single uninterrupted
  # run would emit. The token is derived from the guarantee timestamp in the checkpoint, so a re-run
  # against that checkpoint reproduces it; a run that had silently started over would not.
  rerun=$(rerun_job "$LEDGER_NS" "$LEDGER_JOB" "$manifest" 600)
  rerun_token=$(extract_completion_token finalize-ledger "$rerun")
  [ "$rerun_token" = "$token" ] || {
    echo "::error::finalize-ledger emitted a different token on the re-run: $token vs $rerun_token"
    exit 1
  }

  save_handover ledger-token "$token"
  echo "finalize-ledger completed; its token survived the interruption unchanged."
}

# --- Phase: Step 2 (Auditor AD), first attempt: interrupt finalize-auditor -----------------------
# Stops the command after it has captured and persisted its guarantee timestamp but before it has
# finished, so the workflow can commit new transactions that are strictly newer than that timestamp.
phase_interrupt_finalize_auditor() {
  echo "== Step 2 (first attempt): finalize-auditor, interrupted midway =="
  prepare_auditor_ad

  # Retries disabled so the Job stays down after the kill instead of racing the new traffic.
  render_without_retry "$MANIFESTS/auditor-ad/finalize-auditor.yaml" \
    > "$work/auditor-ad/finalize-auditor-no-retry.yaml"

  local outcome result failed_pod remaining
  apply_job "$AUDITOR_NS" "$AUDITOR_JOB" "$work/auditor-ad/finalize-auditor-no-retry.yaml"
  outcome=$(interrupt_job "$AUDITOR_NS" "$AUDITOR_JOB" "$AUDITOR_START_MARKER" 900)
  result=$(wait_for_job_terminal "$AUDITOR_NS" "$AUDITOR_JOB" 900)
  echo "the first attempt ended as $result ($outcome)"

  if [ "$result" = Failed ]; then
    # A force-deleted Pod leaves no Failed Pod behind, so one here would mean the command itself
    # errored out -- which is not the interruption this phase is arranging.
    failed_pod=$(job_pod "$AUDITOR_NS" "$AUDITOR_JOB" Failed)
    [ -z "$failed_pod" ] || diag_and_die "$AUDITOR_NS" \
      "the interrupted finalize-auditor attempt failed with a tool error, not the interruption"
  fi

  # Whatever request_proof rows are still here predate the guarantee timestamp this attempt
  # persisted, so the resumed run must delete exactly these and keep everything added afterwards.
  remaining=$(count_cosmos_items "$auditor_uri" "$auditor_key" auditor request_proof)
  save_handover request-proof-count-before-traffic "$remaining"
  echo "request_proof rows left by the interrupted attempt: $remaining"
}

# --- Phase: Step 2 (Auditor AD), resumed: finalize-auditor ---------------------------------------
phase_finalize_auditor() {
  echo "== Step 2 (resumed): finalize-auditor =="
  prepare_auditor_ad

  local manifest="$work/auditor-ad/finalize-auditor.yaml"
  local before pre_traffic new_rows output token after rerun rerun_token rerun_after
  before=$(count_cosmos_items "$auditor_uri" "$auditor_key" auditor request_proof)
  pre_traffic=$(load_handover request-proof-count-before-traffic)
  new_rows=$((before - pre_traffic))
  echo "request_proof rows: $before now, $pre_traffic before the new traffic (new: $new_rows)"
  [ "$new_rows" -gt 0 ] || {
    echo "::error::the new traffic added no request_proof rows, so this phase would prove nothing"
    exit 1
  }

  apply_job "$AUDITOR_NS" "$AUDITOR_JOB" "$manifest"
  wait_for_job "$AUDITOR_NS" "$AUDITOR_JOB" 900
  # The resumed run must pick the interrupted attempt's checkpoint up rather than capture a fresh
  # guarantee timestamp, which is what keeps the new traffic outside the deletion window.
  assert_job_log_contains "$AUDITOR_NS" "$AUDITOR_JOB" Succeeded "$RESUME_MARKER"
  output=$(read_job_output_json "$AUDITOR_NS" "$AUDITOR_JOB")
  echo "finalize-auditor output: $output"
  token=$(extract_completion_token finalize-auditor "$output")

  # Every record registered before the guarantee timestamp is gone, and every record the new traffic
  # created after it is still there. The Auditor's own transaction state purge is disabled (its
  # default), so the tool is the only thing that can delete a request_proof record here.
  after=$(count_cosmos_items "$auditor_uri" "$auditor_key" auditor request_proof)
  echo "request_proof rows after finalize-auditor: $after (expected $new_rows)"
  [ "$after" -eq "$new_rows" ] || {
    echo "::error::finalize-auditor left $after request_proof rows (expected the $new_rows rows of new traffic)"
    exit 1
  }

  # Re-running the command must be a no-op: same token, and the new traffic's records untouched.
  rerun=$(rerun_job "$AUDITOR_NS" "$AUDITOR_JOB" "$manifest" 900)
  rerun_token=$(extract_completion_token finalize-auditor "$rerun")
  [ "$rerun_token" = "$token" ] || {
    echo "::error::finalize-auditor emitted a different token on the re-run: $token vs $rerun_token"
    exit 1
  }
  rerun_after=$(count_cosmos_items "$auditor_uri" "$auditor_key" auditor request_proof)
  [ "$rerun_after" -eq "$after" ] || {
    echo "::error::the finalize-auditor re-run changed the request_proof rows: $after -> $rerun_after"
    exit 1
  }

  save_handover auditor-token "$token"
  echo "finalize-auditor completed; the records created after its guarantee timestamp survived."
}

# --- Phase: Step 3 (Ledger AD): cleanup-coordinator ----------------------------------------------
phase_cleanup_coordinator() {
  echo "== Step 3: cleanup-coordinator =="
  prepare_ledger_ad

  local token_l token_a
  token_l=$(load_handover ledger-token)
  token_a=$(load_handover auditor-token)

  reject_mixed_tokens "$token_l" "$token_a"

  # The Job manifest takes both tokens as ${LEDGER_TOKEN} / ${AUDITOR_TOKEN}.
  export LEDGER_TOKEN="$token_l" AUDITOR_TOKEN="$token_a"

  local manifest="$work/ledger-ad/cleanup-coordinator.yaml"
  local before after deleted expected_before expected_after rerun rerun_after
  before=$(count_cosmos_items "$ledger_uri" "$ledger_key" coordinator state)
  run_interrupted_and_resumed "$LEDGER_NS" "$COORDINATOR_JOB" "$manifest" \
    "$COORDINATOR_START_MARKER" 600
  local output
  output=$(read_job_output_json "$LEDGER_NS" "$COORDINATOR_JOB")
  echo "cleanup-coordinator output: $output"
  [ "$(printf '%s' "$output" | jq -r '.status_code')" = "OK" ] \
    || { echo "::error::cleanup-coordinator did not report OK"; exit 1; }

  after=$(count_cosmos_items "$ledger_uri" "$ledger_key" coordinator state)
  deleted=$((before - after))
  # Only the RECORD_COUNT committed put transactions settled before the deletable-before boundary,
  # so exactly those are removed. What must survive is
  #   2 * RECORD_COUNT  the nonce aborts finalize-auditor wrote while recovering the stranded write
  #                     and read locks, all after the boundary, and
  #   1 * RECORD_COUNT  the transactions of the new traffic the workflow ran after both guarantee
  #                     timestamps were captured -- the records this test must prove are left alone.
  expected_before=$((4 * RECORD_COUNT))
  expected_after=$((3 * RECORD_COUNT))
  echo "coordinator.state rows: before=$before after=$after deleted=$deleted"
  [ "$before" -eq "$expected_before" ] \
    || { echo "::error::coordinator.state had $before rows before cleanup (expected $expected_before)"; exit 1; }
  [ "$deleted" -eq "$RECORD_COUNT" ] \
    || { echo "::error::cleanup-coordinator deleted $deleted coordinator.state rows (expected $RECORD_COUNT)"; exit 1; }
  [ "$after" -eq "$expected_after" ] \
    || { echo "::error::coordinator.state has $after rows after cleanup (expected $expected_after)"; exit 1; }

  # Re-running the command must delete nothing more.
  rerun=$(rerun_job "$LEDGER_NS" "$COORDINATOR_JOB" "$manifest" 600)
  [ "$(printf '%s' "$rerun" | jq -r '.status_code')" = "OK" ] \
    || { echo "::error::the cleanup-coordinator re-run did not report OK"; exit 1; }
  rerun_after=$(count_cosmos_items "$ledger_uri" "$ledger_key" coordinator state)
  [ "$rerun_after" -eq "$after" ] || {
    echo "::error::the cleanup-coordinator re-run changed the coordinator.state rows: $after -> $rerun_after"
    exit 1
  }

  echo "cleanup-coordinator deleted only the settled records; the new traffic's records survived."
}

# Swapping the two tokens must be refused by the server-type validation before anything is deleted.
# This runs while no coordinator-cleanup checkpoint exists yet: once there is one, the tokens are
# ignored on purpose so that a resumed run keeps its original deletion boundary.
# Usage: reject_mixed_tokens <ledger-token> <auditor-token>
reject_mixed_tokens() {
  echo "== Negative: cleanup-coordinator with the two tokens swapped =="
  local manifest="$work/ledger-ad/cleanup-coordinator-mixed-tokens.yaml" result output
  render_without_retry "$MANIFESTS/ledger-ad/cleanup-coordinator.yaml" \
    | sed "s|^\( *\)name: $COORDINATOR_JOB\$|\1name: $MIXED_TOKENS_JOB|" > "$manifest"

  # The Auditor token goes to --ledger-token and vice versa. envsubst only sees exported variables,
  # and the caller exports the correct pairing right after this function returns.
  export LEDGER_TOKEN="$2" AUDITOR_TOKEN="$1"
  apply_job "$LEDGER_NS" "$MIXED_TOKENS_JOB" "$manifest"
  result=$(wait_for_job_terminal "$LEDGER_NS" "$MIXED_TOKENS_JOB" 600)
  [ "$result" = Failed ] || {
    echo "::error::cleanup-coordinator accepted the swapped tokens (job ended as $result)"
    exit 1
  }
  output=$(read_job_error_json "$LEDGER_NS" "$MIXED_TOKENS_JOB")
  # DL-TOOLS-1005 is LEDGER_TOKEN_WRONG_SERVER_TYPE: the Ledger token is validated first.
  assert_error_json "$output" USER_ERROR DL-TOOLS-1005

  kubectl -n "$LEDGER_NS" delete "job/$MIXED_TOKENS_JOB" --ignore-not-found
}

case "${1:-}" in
  reject-old-auditor)         phase_reject_old_auditor ;;
  finalize-ledger)            phase_finalize_ledger ;;
  interrupt-finalize-auditor) phase_interrupt_finalize_auditor ;;
  finalize-auditor)           phase_finalize_auditor ;;
  cleanup-coordinator)        phase_cleanup_coordinator ;;
  *)
    echo "usage: run-cleanup.sh [reject-old-auditor|finalize-ledger|interrupt-finalize-auditor|finalize-auditor|cleanup-coordinator]" >&2
    exit 1
    ;;
esac
