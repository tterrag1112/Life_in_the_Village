#!/bin/bash
# SessionStart hook — fail-fast if local `main` is stale vs `origin/main`.
#
# Background: the remote execution sandbox has been observed to boot with
# a stale local `main` that's 40+ commits behind `origin/main`. Inspection
# agents then search a stale tree and produce wrong-baseline findings.
# This hook catches that at session start so the session can be aborted
# before any work happens against the wrong code.
#
# Behavior:
#   - Local-only sessions (CLAUDE_CODE_REMOTE unset): no-op exit 0.
#   - Remote sessions: fetch origin, compare local `main` to `origin/main`,
#     emit STALE diagnostic and exit non-zero if they differ.
#   - No destructive operations. No `git reset --hard`. Failure surfaces
#     the diagnosis; the operator (or a follow-up hook) decides how to
#     recover.
set -euo pipefail

# Run remotely only — local dev sessions don't need this guard.
if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

cd "${CLAUDE_PROJECT_DIR:-$(pwd)}"

if ! git rev-parse --git-dir >/dev/null 2>&1; then
  echo "session-start: not a git repository, skipping staleness check" >&2
  exit 0
fi

# Fetch quietly; tolerate transient network errors by NOT failing the
# hook on fetch failure alone — only fail if the comparison shows drift.
if ! git fetch origin main --quiet 2>/dev/null; then
  echo "session-start: WARN: git fetch origin main failed; proceeding with cached refs" >&2
fi

# `main` may not exist locally on every checkout style; tolerate that.
if ! LOCAL=$(git rev-parse main 2>/dev/null); then
  echo "session-start: WARN: no local 'main' ref; skipping staleness check" >&2
  exit 0
fi
if ! REMOTE=$(git rev-parse origin/main 2>/dev/null); then
  echo "session-start: WARN: no 'origin/main' ref; skipping staleness check" >&2
  exit 0
fi

if [ "$LOCAL" != "$REMOTE" ]; then
  cat >&2 <<EOF
================================================================
STALE: local main does not match origin/main.
  local  main        = $LOCAL
  origin/main        = $REMOTE
The sandbox booted on an outdated snapshot. Any inspection against
this working tree will return wrong-baseline findings. DO NOT
proceed with code work until this is reset.

To recover (destructive — discards local main commits):
  git fetch origin
  git checkout main
  git reset --hard origin/main
================================================================
EOF
  exit 1
fi

exit 0
