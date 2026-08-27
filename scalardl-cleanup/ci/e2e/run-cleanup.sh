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
source "$HERE/cleanup-jobs.sh"

require_vars CLEANUP_VERSION COSMOSDB_SHELL RUNNER_TEMP RECORD_COUNT

# The Job manifests take the image tag as ${CLEANUP_VERSION}; the namespace is not in them and is
# passed to kubectl instead. LEDGER_NS / AUDITOR_NS come from common.sh, via cleanup-jobs.sh.
export CLEANUP_VERSION

init_manifests_workdir
load_ad_credentials

# --- Step 1 (Ledger AD): finalize-ledger -------------------------------------------------------
echo "== Step 1: finalize-ledger =="

setup_ledger_ad

# Run the Job.
apply_job "$LEDGER_NS" scalardl-finalize-ledger "$work/ledger-ad/finalize-ledger.yaml"
wait_for_job "$LEDGER_NS" scalardl-finalize-ledger 600
ledger_out=$(read_job_output_json "$LEDGER_NS" scalardl-finalize-ledger)
echo "finalize-ledger output: $ledger_out"
token_l=$(extract_completion_token finalize-ledger "$ledger_out")

# --- Step 2 (Auditor AD): finalize-auditor -----------------------------------------------------
echo "== Step 2: finalize-auditor =="

setup_auditor_ad

# Run the Job. Recovering a stranded lock can wait out the lock's valid period, hence the longer
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
