#!/usr/bin/env bash
#
# Scenario: finalize-auditor with --skip-request-proof-cleanup, then again without it on the same
# checkpoint. The first run has to release the locks and print its token while leaving every request
# proof in place; the second has to print that same token and delete them.
#
# Required environment:
#   CLEANUP_VERSION           tag of the scalardl-cleanup image
#   COSMOSDB_SHELL            path to the Azure Cosmos DB Shell binary, used to count rows in Cosmos
#   RECORD_COUNT              records generated per category
#   RUNNER_TEMP               scratch directory for the rendered manifests and the populated-assets
#                             metadata
#   SCALARDL_NEW_VERSION      the version the cleanup commands coordinate with
# plus what setup-fixture.sh and populate.sh need, and a kubectl context with the namespaces
# deployed.

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
E2E="$HERE/.."
source "$E2E/run-cleanup.sh"

require_vars RUNNER_TEMP RECORD_COUNT SCALARDL_NEW_VERSION

echo "== Rebuild the fixture =="
"$E2E/setup-fixture.sh"

echo "== Upgrade the servers to $SCALARDL_NEW_VERSION =="
SCALARDL_VERSION="$SCALARDL_NEW_VERSION" "$E2E/manage-cluster.sh" change-version

init_cleanup_commands

# As an operator would: the Ledger side is finalized before the Auditor side.
run_finalize_ledger

echo "== Run finalize-auditor with --skip-request-proof-cleanup =="

# The manifest ships the option commented out; uncommenting it here also proves that line is one
# the command accepts.
skip_manifest="$work/auditor-ad/finalize-auditor-skip.yaml"
sed -E 's|^([[:space:]]*)# (- --skip-request-proof-cleanup)$|\1\2|' \
  "$work/auditor-ad/finalize-auditor.yaml" > "$skip_manifest"
grep -Eq '^[[:space:]]*- --skip-request-proof-cleanup$' "$skip_manifest" \
  || { echo "::error::the manifest has no commented-out --skip-request-proof-cleanup to enable"; exit 1; }

rp_before=$(count_cosmos_records "$auditor_uri" "$auditor_key" auditor request_proof)
# The request proofs have to be there first, or their surviving the run proves nothing.
[ "$rp_before" -gt 0 ] \
  || { echo "::error::the fixture left no request_proof rows"; exit 1; }

run_finalize_auditor "$skip_manifest"
skip_token="$auditor_token"

rp_after_skip=$(count_cosmos_records "$auditor_uri" "$auditor_key" auditor request_proof)
echo "request_proof rows: before=$rp_before after=$rp_after_skip"
[ "$rp_after_skip" -eq "$rp_before" ] \
  || { echo "::error::finalize-auditor changed request_proof from $rp_before to $rp_after_skip despite --skip-request-proof-cleanup"; exit 1; }
held=$(count_cosmos_held_locks "$auditor_uri" "$auditor_key" auditor)
[ "$held" -eq 0 ] || { echo "::error::finalize-auditor left $held asset locks held"; exit 1; }

echo "== Run finalize-auditor again without the option, on the same checkpoint =="

# Wait for the first run's Pod to be gone: both runs' Pods carry the same job-name label, so reading
# the second run's output could otherwise pick up the first's. The checkpoint volume stays.
kubectl -n "$AUDITOR_NS" delete job/scalardl-finalize-auditor --cascade=foreground --timeout=120s

run_finalize_auditor

# The same token shows the run resumed from the checkpoint rather than starting over, so the
# deletable-before boundary is still the one the first run fixed.
[ "$auditor_token" = "$skip_token" ] \
  || { echo "::error::the second run printed a different completion token than the first"; exit 1; }
rp_after=$(count_cosmos_records "$auditor_uri" "$auditor_key" auditor request_proof)
echo "request_proof rows: before=$rp_after_skip after=$rp_after"
[ "$rp_after" -eq 0 ] \
  || { echo "::error::finalize-auditor left $rp_after request_proof rows (expected 0)"; exit 1; }

echo "The option spared every request proof, and running without it afterwards deleted them."
