#!/usr/bin/env bash
#
# Run the three ScalarDL Cleanup commands against the deployed E2E cluster and assert each
# one succeeds.
#
# Expects in the environment:
#   CLEANUP      path to the scalardl-cleanup binary (set by the workflow)
#   RUNNER_TEMP  scratch directory for checkpoints and per-command stderr logs
# and a kubectl context with the ledger-e2e / auditor-e2e namespaces deployed.

set -euo pipefail

for v in CLEANUP RUNNER_TEMP; do
  if [[ -z "${!v:-}" ]]; then echo "ERROR: $v must be set" >&2; exit 1; fi
done

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/port-forward.sh"
# Tear down the background port-forwards on exit (success or failure) so a standalone or
# interrupted run leaves no dangling kubectl processes.
trap pf_reset EXIT

ckpt="$RUNNER_TEMP/checkpoint"
ledger_props="$RUNNER_TEMP/ledger.properties"
auditor_props="$RUNNER_TEMP/auditor.properties"

# finalize-auditor is the only command that talks to a server.
pf_reset
pf_start auditor-e2e svc/auditor 40051:40051 40052:40052
pf_wait 40051 40052

# The tool takes the servers' properties files as-is. Pull them straight from the deployed
# config secrets so the tool sees exactly the ScalarDB configuration each server uses.
kubectl -n ledger-e2e  get secret ledger-config  -o jsonpath='{.data.ledger\.properties}'  | base64 -d > "$ledger_props"
kubectl -n auditor-e2e get secret auditor-config -o jsonpath='{.data.auditor\.properties}' | base64 -d > "$auditor_props"
# finalize-auditor additionally needs ScalarDL client properties to reach the Auditor server's
# privileged port. The leading newline guards against the Secret value lacking a trailing newline.
{ echo; cat "$HERE/client.properties"; } >> "$auditor_props"

# Run one cleanup subcommand and echo its stdout JSON. On failure, surface the captured stderr and
# return non-zero so the caller's `set -e` aborts. Usage: run_tool <subcommand> [args...]
run_tool() {
  local sub="$1" out
  if ! out=$("$CLEANUP" "$@" --checkpoint-dir "$ckpt" 2>"$RUNNER_TEMP/$sub.err"); then
    cat "$RUNNER_TEMP/$sub.err" >&2
    echo "::error::$sub failed" >&2
    return 1
  fi
  printf '%s\n' "$out"
}

# Extract a non-empty completion token from a finalize command's JSON, or fail.
completion_token() {
  local sub="$1" json="$2" token
  token=$(printf '%s' "$json" | jq -r '.output.completion_token // empty')
  [ -n "$token" ] || { echo "::error::$sub emitted no completion token"; return 1; }
  printf '%s\n' "$token"
}

echo "== Execute finalize-ledger =="
ledger_out=$(run_tool finalize-ledger --properties "$ledger_props")
echo "$ledger_out"
token_l=$(completion_token finalize-ledger "$ledger_out")

echo "== Execute finalize-auditor =="
auditor_out=$(run_tool finalize-auditor --properties "$auditor_props")
echo "$auditor_out"
token_a=$(completion_token finalize-auditor "$auditor_out")

echo "== Execute cleanup-coordinator =="
coord_out=$(run_tool cleanup-coordinator --properties "$ledger_props" \
              --ledger-token "$token_l" --auditor-token "$token_a")
echo "$coord_out"
[ "$(printf '%s' "$coord_out" | jq -r '.status_code')" = "OK" ] \
  || { echo "::error::cleanup-coordinator did not report OK"; exit 1; }

echo "All three cleanup commands succeeded."
