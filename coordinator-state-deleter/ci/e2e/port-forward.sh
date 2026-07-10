#!/usr/bin/env bash
#
# Shared kubectl port-forward helpers for the E2E cluster, used by manage-cluster.sh and
# .github/workflows/e2e-verify.yaml. Source this file — it only defines functions
# (and PF_PIDS); it runs nothing on its own. Because callers source it, the
# port-forwards start in the caller's shell and survive for a subsequent command
# (e.g. gradle) in the same step.
#
#   source ci/e2e/port-forward.sh
#   pf_reset                                        # kill leftovers from prior steps
#   pf_start "$LNS" svc/ledger  50051:50051 50052:50052
#   pf_start "$ANS" svc/auditor 40051:40051 40052:40052
#   pf_wait 50051 50052 40051 40052                 # block until reachable
#   ./gradlew ...

# PIDs of port-forwards started via pf_start (for pf_kill in a trap).
PF_PIDS=()

# TCP connect check via a bash builtin (no extra tooling). Uses 127.0.0.1 (IPv4)
# because kubectl port-forward binds IPv4 only; "localhost" may resolve to ::1.
pf_check_tcp() { timeout 3 bash -c "exec 3<>/dev/tcp/127.0.0.1/$1" 2>/dev/null; }

# Kill any leftover kubectl port-forwards and reset the PID list. Leftovers from a
# previous CI step keep their local port bound to a now-deleted pod (after a
# redeploy), which surfaces mid-run as "connection refused" / "container not
# running". Call this before starting fresh forwards.
pf_reset() {
  pkill -f 'kubectl.*port-forward' 2>/dev/null || true
  PF_PIDS=()
  sleep 2
}

# Start a background port-forward: pf_start <ns> <target> <local:remote>...
# Logs to /tmp/pf-<first-local-port>.log so failure diagnostics can collect it.
pf_start() {
  local ns="$1" target="$2"
  shift 2
  local first_local="${1%%:*}"
  kubectl -n "$ns" port-forward "$target" "$@" >"/tmp/pf-${first_local}.log" 2>&1 &
  PF_PIDS+=("$!")
}

# Block until each given local port accepts a TCP connection (~30s each), else
# fail. Returns non-zero on timeout so `set -e` callers abort.
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
      return 1
    }
  done
}

# Kill tracked port-forwards (use from a trap).
pf_kill() {
  local pid
  for pid in "${PF_PIDS[@]:-}"; do
    [ -n "$pid" ] && kill "$pid" 2>/dev/null || true
  done
}
