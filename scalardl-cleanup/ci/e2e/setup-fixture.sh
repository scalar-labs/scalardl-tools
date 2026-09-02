#!/usr/bin/env bash
#
# Rebuild the fixture a scenario starts from: an empty schema, empty checkpoint volumes, and the
# garbage an OLD ScalarDL server leaves behind. It leaves the servers on the OLD version, so a
# scenario that wants them upgraded runs `manage-cluster.sh change-version` itself.
#
# Required environment:
#   SCALARDL_OLD_VERSION                 the version that leaves the garbage behind
#   COSMOS_URI / COSMOS_KEY              the schema-loader Jobs are rendered with these
#   CLIENT / RECORD_COUNT / RUNNER_TEMP  populate.sh needs these
#
# Usage:
#   ./setup-fixture.sh

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# LEDGER_NS / AUDITOR_NS and require_vars come from common.sh; reset_checkpoint from cleanup-jobs.sh.
source "$HERE/cleanup-jobs.sh"

# Check everything up front: the checkpoint reset below destroys state, so a missing variable should
# stop us before that rather than halfway through.
require_vars SCALARDL_OLD_VERSION COSMOS_URI COSMOS_KEY CLIENT RECORD_COUNT RUNNER_TEMP

# The metadata describes what this fixture populated, so the previous one's must not survive it.
rm -f "$RUNNER_TEMP/populated-assets.json"

echo "==> reset the checkpoint volumes"
reset_checkpoint "$LEDGER_NS"
reset_checkpoint "$AUDITOR_NS"

echo "==> put the servers on ${SCALARDL_OLD_VERSION}"
SCALARDL_VERSION="$SCALARDL_OLD_VERSION" "$HERE/manage-cluster.sh" change-version

echo "==> reset the schema"
SCALARDL_VERSION="$SCALARDL_OLD_VERSION" "$HERE/manage-cluster.sh" reset-schema

echo "==> populate data"
"$HERE/populate.sh" commit-objects
"$HERE/populate.sh" strand-locks

echo "SETUP-FIXTURE OK: the garbage is in place and the servers are on ${SCALARDL_OLD_VERSION}."
