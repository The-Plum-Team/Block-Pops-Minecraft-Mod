#!/usr/bin/env bash
# Compatibility entry point for local/CI callers. The old Loom development-run
# implementation is intentionally gone; all arguments are packaged-runner flags.
set -euo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
exec python3 "$REPO/e2e/orchestrator.py" --packaged "$@"
