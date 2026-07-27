#!/usr/bin/env bash

set -euo pipefail

# Let the JVM size its heap relative to the container's memory limit. This is
# the same mechanism the ScalarDL Ledger/Auditor images use.
export JAVA_OPTS="${JAVA_OPTS:-} -XX:MaxRAMPercentage=${JAVA_OPT_MAX_RAM_PERCENTAGE}"

exec ./bin/scalardl-cleanup "$@"
