#!/usr/bin/env bash
#
# Dump what a failed step left behind in the cluster: workloads, PVCs, events, `describe pods` and
# the Pod logs for both namespaces, plus the port-forward logs.
#
# Collected per step rather than once at the end of the job: each scenario starts by rebuilding the
# fixture, which deletes the cleanup Jobs with their Pods and restarts both servers, so a dump taken
# at the end would describe the last scenario's cluster, not the one that failed.
#
# Never exits non-zero: the caller has already failed, and failing here would only bury it.
#
# Usage:
#   ./collect-diagnostics.sh <label>   # the label names the failed step in the output

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# LEDGER_NS / AUDITOR_NS come from common.sh.
source "$HERE/common.sh"

echo "########## diagnostics after: ${1:-an unnamed step} ##########"

for ns in "$LEDGER_NS" "$AUDITOR_NS"; do
  echo "===== namespace: $ns ====="
  kubectl -n "$ns" get all -o wide
  # `get all` omits PVCs; the cleanup Jobs cannot start without their checkpoint volume.
  kubectl -n "$ns" get pvc -o wide
  kubectl -n "$ns" get events --sort-by=.lastTimestamp
  kubectl -n "$ns" describe pods
  for p in $(kubectl -n "$ns" get pods -o name 2>/dev/null); do
    echo "----- logs $p -----"
    kubectl -n "$ns" logs "$p" --all-containers --prefix --tail=300
  done
done

echo "===== port-forward logs ====="
cat /tmp/pf-*.log 2>/dev/null

exit 0
