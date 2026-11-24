#!/usr/bin/env bash
#
# The operator-facing ScalarDL Cleanup commands, each run as a Kubernetes Job from
# scalardl-cleanup/manifests. Source it:
#
#   source ci/e2e/run-cleanup.sh
#
# Required environment:
#   CLEANUP_VERSION  tag of the scalardl-cleanup image
#   RUNNER_TEMP      scratch directory for the rendered manifests
# and a kubectl context with the ledger-e2e / auditor-e2e namespaces deployed.
#
# A run_* function fails if its command cannot be carried out, but never judges the result: whether
# what a command reclaimed is what the test expects is the scenario's call, which the assert_*
# functions are there for.

E2E_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$E2E_DIR/cleanup-jobs.sh"

# Prepare what every step needs. Call once, before the first step.
# Usage: init_cleanup_commands
init_cleanup_commands() {
  require_vars CLEANUP_VERSION RUNNER_TEMP

  # The Job manifests take the image tag as ${CLEANUP_VERSION}; the namespace is not in them and is
  # passed to kubectl instead. LEDGER_NS / AUDITOR_NS come from common.sh, via cleanup-jobs.sh.
  export CLEANUP_VERSION

  init_manifests_workdir
  load_ad_credentials
}

# Run finalize-ledger (Ledger AD). Sets ledger_token.
# Usage: run_finalize_ledger
run_finalize_ledger() {
  local out
  setup_ledger_ad
  apply_job "$LEDGER_NS" scalardl-finalize-ledger "$work/ledger-ad/finalize-ledger.yaml"
  wait_for_job "$LEDGER_NS" scalardl-finalize-ledger 600
  out=$(read_job_output_json "$LEDGER_NS" scalardl-finalize-ledger)
  echo "finalize-ledger output: $out"
  ledger_token=$(extract_completion_token finalize-ledger "$out")
}

# Run finalize-ledger (Ledger AD) expecting it to fail. Sets finalize_ledger_error to the error JSON.
# An earlier run's Pods go first and are waited for: they carry the same job-name label, so a failed
# one among them could otherwise be the one the output is read from. The checkpoint volume stays.
# Usage: run_finalize_ledger_expect_failure
run_finalize_ledger_expect_failure() {
  setup_ledger_ad
  kubectl -n "$LEDGER_NS" delete job/scalardl-finalize-ledger \
    --ignore-not-found --cascade=foreground --timeout=120s
  finalize_ledger_error=$(run_job_expect_failure "$LEDGER_NS" scalardl-finalize-ledger \
    "$work/ledger-ad/finalize-ledger.yaml")
  echo "finalize-ledger output: $finalize_ledger_error"
}

# Run finalize-auditor (Auditor AD). Sets auditor_token. Recovering a stranded lock can wait out the
# lock's valid period, hence the longer timeout. The manifest defaults to the one in the working copy;
# a scenario passes its own to run the command with different arguments.
# Usage: run_finalize_auditor [manifest]
run_finalize_auditor() {
  local out manifest="${1:-$work/auditor-ad/finalize-auditor.yaml}"
  setup_auditor_ad
  apply_job "$AUDITOR_NS" scalardl-finalize-auditor "$manifest"
  wait_for_job "$AUDITOR_NS" scalardl-finalize-auditor 900
  out=$(read_job_output_json "$AUDITOR_NS" scalardl-finalize-auditor)
  echo "finalize-auditor output: $out"
  auditor_token=$(extract_completion_token finalize-auditor "$out")
}

# Run cleanup-coordinator (Ledger AD). Sets cleanup_coordinator_output to the command's JSON.
# Usage: run_cleanup_coordinator <ledger-token> <auditor-token>
run_cleanup_coordinator() {
  # The Job manifest takes both tokens as ${LEDGER_TOKEN} / ${AUDITOR_TOKEN}.
  export LEDGER_TOKEN="$1" AUDITOR_TOKEN="$2"
  apply_job "$LEDGER_NS" scalardl-cleanup-coordinator "$work/ledger-ad/cleanup-coordinator.yaml"
  wait_for_job "$LEDGER_NS" scalardl-cleanup-coordinator 600
  cleanup_coordinator_output=$(read_job_output_json "$LEDGER_NS" scalardl-cleanup-coordinator)
  echo "cleanup-coordinator output: $cleanup_coordinator_output"
}

# Run a verify command, whose Job is expected to complete (passed) or to fail (failed). Sets
# verify_output to the command's JSON and verify_pod_count to the number of Pods the Job started.
# Usage: run_verify_job <namespace> <job> <manifest> <passed|failed>
run_verify_job() {
  local ns="$1" job="$2" manifest="$3" expected="$4"
  if [ "$expected" = passed ]; then
    apply_job "$ns" "$job" "$manifest"
    wait_for_job "$ns" "$job" 600
    verify_output=$(read_job_output_json "$ns" "$job")
  else
    verify_output=$(run_job_expect_failure "$ns" "$job" "$manifest")
  fi
  verify_pod_count=$(kubectl -n "$ns" get pod -l "job-name=$job" --no-headers | wc -l | tr -d ' ')
  echo "$job output: $verify_output (pods: $verify_pod_count)"
}

# Run verify-ledger-finalization (Ledger AD). Sets verify_output and verify_pod_count.
# Usage: run_verify_ledger_finalization <passed|failed>
run_verify_ledger_finalization() {
  setup_ledger_ad
  run_verify_job "$LEDGER_NS" scalardl-verify-ledger-finalization \
    "$work/ledger-ad/verify-ledger-finalization.yaml" "$1"
}

# Run verify-auditor-finalization (Auditor AD). Sets verify_output and verify_pod_count.
# Usage: run_verify_auditor_finalization <passed|failed>
run_verify_auditor_finalization() {
  setup_auditor_ad
  run_verify_job "$AUDITOR_NS" scalardl-verify-auditor-finalization \
    "$work/auditor-ad/verify-auditor-finalization.yaml" "$1"
}

# Abort unless the verify command that just ran found something: a verdict, not a failure to reach
# one, and reported by a single Pod, since a retry cannot change it.
# Usage: assert_verification_failed <command>
assert_verification_failed() {
  [ "$(printf '%s' "$verify_output" | jq -r '.status_code')" = "VERIFICATION_FAILED" ] \
    || { echo "::error::$1 did not report VERIFICATION_FAILED"; exit 1; }
  [ "$verify_pod_count" -eq 1 ] \
    || { echo "::error::$1 ran $verify_pod_count Pods (expected 1: no retry on a verdict)"; exit 1; }
}

# Abort unless the verify command that just ran found nothing.
# Usage: assert_verification_passed <command>
assert_verification_passed() {
  [ "$(printf '%s' "$verify_output" | jq -r '.status_code')" = "OK" ] \
    || { echo "::error::$1 did not report OK"; exit 1; }
}
