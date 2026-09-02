#!/usr/bin/env bash
#
# Scenario: each cleanup command interrupted once and left to resume from the same checkpoint. What
# it reclaims, and the completion token it emits, must come out the same as an uninterrupted run.
#
# The Job's own backoffLimit does the resuming: deleting the Pod leaves the checkpoint PVC behind,
# so the replacement picks the run up. For the finalize commands the proof is the start timestamp --
# their completion token is derived from it, so an identical timestamp means an identical token.
#
# Catching a scan mid-flight needs it to last long enough, so this scenario rebuilds the fixture
# with more records than the others.
#
# Required environment:
#   CLEANUP_VERSION           tag of the scalardl-cleanup image
#   COSMOSDB_SHELL            path to the Azure Cosmos DB Shell binary
#   RECORD_COUNT              records per category. e2e.yaml overrides the other scenarios' value
#                             for this step, because the scan has to walk enough rows to still be
#                             running when the Pod is deleted
#   RUNNER_TEMP               scratch directory for the rendered manifests and the Job logs
#   SCALARDL_NEW_VERSION      the version the cleanup commands coordinate with
# plus what setup-fixture.sh and populate.sh need, and a kubectl context with the namespaces
# deployed.

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
E2E="$HERE/.."
source "$E2E/run-cleanup.sh"

require_vars RUNNER_TEMP RECORD_COUNT SCALARDL_NEW_VERSION

# The line the scanner writes once it has the partition list and is about to read rows. Interrupting
# on it, rather than on the "starting a new run" line that precedes any scan state, is what makes
# the replacement Pod resume a scan instead of beginning one.
SCAN_STARTED='Cosmos DB physical partitions:'
# What a Pod logs when it picks up scan state the interrupted run left behind: either it reloaded a
# table's partition list, or it skipped a table that run had already finished. Which of the two
# appears depends on how far the interrupted Pod got, and finalize-ledger sweeps several tables.
SCAN_RESUMED='Loaded [0-9]+ persisted FeedRanges|Skipping already'

# The line each command writes when it begins a run of its own, which is how the assertions tell a
# resumption from a fresh start. Only the finalize commands append the run's start timestamp.
FINALIZE_STARTED='Starting a new run at'
CLEANUP_STARTED='Starting a new run.'

# The resuming Pod must pick the interrupted run up rather than begin its own.
# Usage: assert_resumed <job> <started-marker>
assert_resumed() {
  local job="$1" marker="$2" log
  log="$RUNNER_TEMP/$job-resumed.log"
  grep -q 'resuming the previous run' "$log" \
    || { echo "::error::$job did not resume the interrupted run"; exit 1; }
  if grep -qF "$marker" "$log"; then
    echo "::error::$job started a new run instead of resuming the interrupted one"; exit 1
  fi
  # The checkpoint carries the scan's own state too, not just the run's boundary.
  grep -qE "$SCAN_RESUMED" "$log" \
    || { echo "::error::$job kept no scan state from the interrupted run"; exit 1; }
  # How far the resumed run got, for the log only: the tool logs nothing that would show a mid-page
  # resume from a continuation token, so there is nothing to assert on.
  grep -o 'Scan complete for [^ ]* [0-9]* records' "$log" || true
}

# The finalize commands also log which run they started and which they resumed, so for them the
# timestamps must match -- and the completion token is derived from that timestamp.
# Usage: assert_resumed_same_run <job>
assert_resumed_same_run() {
  local job="$1" started resumed
  assert_resumed "$job" "$FINALIZE_STARTED"
  # `|| true` keeps a no-match (grep exit 1, fatal under `set -o pipefail`) from aborting before the
  # empty-check below can report which log line was missing.
  started=$(grep -o "$FINALIZE_STARTED [^ ]*" "$RUNNER_TEMP/$job-interrupted.log" \
    | tail -n1 | sed 's/.*at //; s/\.$//' || true)
  [ -n "$started" ] || { echo "::error::$job did not log the run it started"; exit 1; }
  resumed=$(grep -o 'resuming the previous run started at [^ ]*' "$RUNNER_TEMP/$job-resumed.log" \
    | tail -n1 | sed 's/.*at //; s/\.$//' || true)
  # finalize-auditor runs two orchestrators, and assert_resumed above is satisfied by either of them
  # resuming. Only the first one logs a timestamp, so an empty value here means it did not resume.
  [ -n "$resumed" ] || { echo "::error::$job did not resume the run it had started"; exit 1; }
  [ "$started" = "$resumed" ] \
    || { echo "::error::$job resumed a run started at $resumed (expected $started)"; exit 1; }
  echo "$job resumed the run started at $started"
}

echo "== Rebuild the fixture with $RECORD_COUNT records per category =="
"$E2E/setup-fixture.sh"

echo "== Upgrade the servers to $SCALARDL_NEW_VERSION =="
SCALARDL_VERSION="$SCALARDL_NEW_VERSION" "$E2E/manage-cluster.sh" change-version

echo "== Run the finalize commands, interrupting each one =="

# One row per query and a single worker: the scan then lasts long enough to interrupt, and it
# checkpoints as often as it can. E2E-only -- the shipped manifests keep the defaults.
extra_cleanup_properties='scalar.dl.tools.scan.cosmos.page_size=1
scalar.dl.tools.scan.cosmos.max_threads=1'

init_cleanup_commands

setup_ledger_ad
interrupt_and_resume "$LEDGER_NS" scalardl-finalize-ledger \
  "$work/ledger-ad/finalize-ledger.yaml" "$SCAN_STARTED"
assert_resumed_same_run scalardl-finalize-ledger
ledger_out=$(read_job_output_json "$LEDGER_NS" scalardl-finalize-ledger)
ledger_token=$(extract_completion_token finalize-ledger "$ledger_out")

rp_before=$(count_cosmos_records "$auditor_uri" "$auditor_key" auditor request_proof)
setup_auditor_ad
interrupt_and_resume "$AUDITOR_NS" scalardl-finalize-auditor \
  "$work/auditor-ad/finalize-auditor.yaml" "$SCAN_STARTED"
assert_resumed_same_run scalardl-finalize-auditor
auditor_out=$(read_job_output_json "$AUDITOR_NS" scalardl-finalize-auditor)
auditor_token=$(extract_completion_token finalize-auditor "$auditor_out")
# Known gap: finalize-auditor runs the lock sweep and then the request_proof cleanup, and the
# interruption always lands in the first. The second therefore runs start to finish inside the
# resumed Pod, so its own checkpoint and resume path are not covered here.
rp_after=$(count_cosmos_records "$auditor_uri" "$auditor_key" auditor request_proof)
echo "request_proof rows: before=$rp_before after=$rp_after"
[ "$rp_after" -eq 0 ] \
  || { echo "::error::finalize-auditor left $rp_after request_proof rows (expected 0)"; exit 1; }
held=$(count_cosmos_held_locks "$auditor_uri" "$auditor_key" auditor)
[ "$held" -eq 0 ] || { echo "::error::finalize-auditor left $held asset locks held"; exit 1; }

echo "== Run cleanup-coordinator, interrupting it =="

cs_before=$(count_cosmos_records "$ledger_uri" "$ledger_key" coordinator state)
export LEDGER_TOKEN="$ledger_token" AUDITOR_TOKEN="$auditor_token"
interrupt_and_resume "$LEDGER_NS" scalardl-cleanup-coordinator \
  "$work/ledger-ad/cleanup-coordinator.yaml" "$SCAN_STARTED"
assert_resumed scalardl-cleanup-coordinator "$CLEANUP_STARTED"
# The resumed Pod gets the same manifest, so the same tokens: what shows the boundary came from the
# checkpoint rather than from recomputing it is that the command says it ignored them.
grep -q 'the specified completion tokens are ignored' \
  "$RUNNER_TEMP/scalardl-cleanup-coordinator-resumed.log" \
  || { echo "::error::cleanup-coordinator did not report ignoring the tokens it was given"; exit 1; }
coord_out=$(read_job_output_json "$LEDGER_NS" scalardl-cleanup-coordinator)
[ "$(printf '%s' "$coord_out" | jq -r '.status_code')" = "OK" ] \
  || { echo "::error::cleanup-coordinator did not report OK"; exit 1; }

# What an uninterrupted run of this fixture reclaims: exactly the committed transactions settled
# before the boundary are removed, and the rows finalize-auditor wrote while aborting the stranded
# nonces survive. This fixture sees no traffic after the tokens, so nothing else is in play.
cs_after=$(count_cosmos_records "$ledger_uri" "$ledger_key" coordinator state)
echo "coordinator.state rows: before=$cs_before after=$cs_after (expected deleted=$RECORD_COUNT)"
[ "$((cs_before - cs_after))" -eq "$RECORD_COUNT" ] \
  || { echo "::error::cleanup-coordinator deleted $((cs_before - cs_after)) coordinator.state rows (expected $RECORD_COUNT)"; exit 1; }
[ "$cs_after" -eq "$((2 * RECORD_COUNT))" ] \
  || { echo "::error::coordinator.state has $cs_after rows after cleanup (expected $((2 * RECORD_COUNT)))"; exit 1; }

# Nothing may still be pointing at the Coordinator records that were just deleted.
for table in asset asset_metadata; do
  unsettled=$(count_cosmos_unsettled_records "$ledger_uri" "$ledger_key" ledger "$table")
  [ "$unsettled" -eq 0 ] \
    || { echo "::error::$table holds $unsettled records left mid-transaction"; exit 1; }
done
echo "no records left mid-transaction in asset or asset_metadata"

echo "== Verify the contracts still run =="
# Interrupting is where a double delete or a skipped record would show up, so exercise the assets
# here too. Two categories of RECORD_COUNT: this fixture sees no traffic after the tokens.
"$E2E/populate.sh" verify-assets "$((2 * RECORD_COUNT))"

echo "Every command resumed its interrupted run and reclaimed what an uninterrupted run would."
