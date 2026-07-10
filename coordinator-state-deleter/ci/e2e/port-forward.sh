#!/usr/bin/env bash
#
# Shared kubectl port-forward helpers for the E2E test cluster. Usage:
#
#   source ci/e2e/port-forward.sh
#   pf_reset # kill leftovers from prior steps
#   pf_start "$LNS" svc/ledger  50051:50051 50052:50052
#   pf_start "$ANS" svc/auditor 40051:40051 40052:40052
#   pf_wait 50051 50052 40051 40052 # block until reachable

# TCP connect check via a bash builtin.
pf_check_tcp() { timeout 3 bash -c "exec 3<>/dev/tcp/127.0.0.1/$1" 2>/dev/null; }

# Kill any leftover kubectl port-forwards.
pf_reset() {
  pkill -f 'kubectl.*port-forward.*(ledger|auditor)' 2>/dev/null || true
  sleep 2
}

# Start a background port-forward: pf_start <ns> <target> <local:remote>...
# Logs to /tmp/pf-<first-local-port>.log so failure diagnostics can collect it.
pf_start() {
  local ns="$1" target="$2"
  shift 2
  local first_local="${1%%:*}"
  kubectl -n "$ns" port-forward "$target" "$@" >"/tmp/pf-${first_local}.log" 2>&1 &
}

# Block until each given local port accepts a TCP connection (~30s each), else fail.
# Returns non-zero on timeout so `set -e` callers abort.
pf_wait() {
  local p i ok
  for p in "$@"; do
    ok=""
    for i in $(seq 1 30); do
      if pf_check_tcp "$p"; then
        ok=1
        break
      fi
      sleep 1
    done
    [ -n "$ok" ] || {
      echo "::error::localhost:$p not reachable through port-forward"
      echo "=== port-forward logs ==="
      tail -n 50 /tmp/pf-*.log 2>/dev/null || true
      return 1
    }
  done
}
