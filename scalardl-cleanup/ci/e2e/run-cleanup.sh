#!/usr/bin/env bash
#
# The three operator-facing ScalarDL Cleanup commands, each run as a Kubernetes Job from
# scalardl-cleanup/manifests. Source it:
#
#   source ci/e2e/run-cleanup.sh
#
# Expects in the environment:
#   CLEANUP_VERSION  tag of the scalardl-cleanup image
#   COSMOSDB_SHELL   path to the Azure Cosmos DB Shell binary, used to count rows in Cosmos
#   RUNNER_TEMP      scratch directory for the rendered ConfigMaps
# and a kubectl context with the ledger-e2e / auditor-e2e namespaces deployed.

E2E_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$E2E_DIR/cleanup-jobs.sh"

# Prepare what every step needs. Call once, before the first step.
init_cleanup_commands() {
  require_vars CLEANUP_VERSION COSMOSDB_SHELL RUNNER_TEMP

  # The Job manifests take the image tag as ${CLEANUP_VERSION}; the namespace is not in them and is
  # passed to kubectl instead. LEDGER_NS / AUDITOR_NS come from common.sh, via cleanup-jobs.sh.
  export CLEANUP_VERSION

  init_manifests_workdir
  load_ad_credentials
}

# Run finalize-ledger (Ledger AD). Echoes the completion token.
run_finalize_ledger() {
  local out
  setup_ledger_ad >&2
  apply_job "$LEDGER_NS" scalardl-finalize-ledger "$work/ledger-ad/finalize-ledger.yaml" >&2
  wait_for_job "$LEDGER_NS" scalardl-finalize-ledger 600 >&2
  out=$(read_job_output_json "$LEDGER_NS" scalardl-finalize-ledger)
  echo "finalize-ledger output: $out" >&2
  extract_completion_token finalize-ledger "$out"
}

# Run finalize-auditor (Auditor AD). Echoes the completion token. Recovering a stranded lock can
# wait out the lock's valid period, hence the longer timeout.
run_finalize_auditor() {
  local out
  setup_auditor_ad >&2
  apply_job "$AUDITOR_NS" scalardl-finalize-auditor "$work/auditor-ad/finalize-auditor.yaml" >&2
  wait_for_job "$AUDITOR_NS" scalardl-finalize-auditor 900 >&2
  out=$(read_job_output_json "$AUDITOR_NS" scalardl-finalize-auditor)
  echo "finalize-auditor output: $out" >&2
  extract_completion_token finalize-auditor "$out"
}

# Run cleanup-coordinator (Ledger AD). Echoes the command's JSON output.
# Usage: run_cleanup_coordinator <ledger-token> <auditor-token>
run_cleanup_coordinator() {
  # The Job manifest takes both tokens as ${LEDGER_TOKEN} / ${AUDITOR_TOKEN}.
  export LEDGER_TOKEN="$1" AUDITOR_TOKEN="$2"
  apply_job "$LEDGER_NS" scalardl-cleanup-coordinator "$work/ledger-ad/cleanup-coordinator.yaml" >&2
  wait_for_job "$LEDGER_NS" scalardl-cleanup-coordinator 600 >&2
  read_job_output_json "$LEDGER_NS" scalardl-cleanup-coordinator
}
