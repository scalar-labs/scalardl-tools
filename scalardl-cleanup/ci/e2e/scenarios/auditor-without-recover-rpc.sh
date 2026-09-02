#!/usr/bin/env bash
#
# Scenario: finalize-auditor against an Auditor that predates the privileged RecoverAssetLock RPC.
# It must fail with DL-TOOLS-1014, which names the missing RPC and tells the operator to upgrade,
# rather than with the raw gRPC UNIMPLEMENTED text.
#
# Runs before the upgrade step, while the cluster is still on the old version and holds the stranded
# locks that make finalize-auditor issue the RPC at all. SCALARDL_OLD_VERSION therefore has to stay
# on a version without the RPC (it arrived in 3.14.0); raising it past that makes the command
# succeed here, which this scenario reports as a failure rather than passing silently.
#
# Required environment:
#   CLEANUP_VERSION  tag of the scalardl-cleanup image
#   COSMOSDB_SHELL   path to the Azure Cosmos DB Shell binary
#   RUNNER_TEMP      scratch directory for the rendered manifests
# and a kubectl context with the ledger-e2e / auditor-e2e namespaces deployed.

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
E2E="$HERE/.."
source "$E2E/run-cleanup.sh"

echo "== Run finalize-auditor against the old-version Auditor =="

init_cleanup_commands
setup_auditor_ad
out=$(run_job_expect_failure "$AUDITOR_NS" scalardl-finalize-auditor \
  "$work/auditor-ad/finalize-auditor.yaml")
echo "finalize-auditor output: $out"

# USER_ERROR rather than INTERNAL_ERROR: an out-of-date server is the operator's to fix.
status=$(printf '%s' "$out" | jq -r '.status_code')
[ "$status" = "USER_ERROR" ] \
  || { echo "::error::finalize-auditor reported status_code=$status (expected USER_ERROR)"; exit 1; }

message=$(printf '%s' "$out" | jq -r '.error_message')
case "$message" in
  DL-TOOLS-1014:*) ;;
  *) echo "::error::finalize-auditor reported '$message' (expected DL-TOOLS-1014)"; exit 1 ;;
esac

# The failed run persisted its start timestamp and its scan progress, so drop the volume: the run
# after the upgrade has to start a new run rather than resume this one.
reset_checkpoint "$AUDITOR_NS"

echo "finalize-auditor failed as expected: $message"
