#!/usr/bin/env bash
#
# Install the repository's committed git hooks.
#
#   ./scripts/setup-git-hooks.sh
#
# This points git at the version-controlled .githooks/ directory so the
# pre-push gate (lint + detekt + spotless + unit tests + assembleDebug) runs on
# every push. Run it once per clone.

set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"

git config core.hooksPath .githooks
chmod +x .githooks/* 2>/dev/null || true

echo "Git hooks installed: core.hooksPath -> .githooks"
echo "The pre-push gate will now run before every push."
echo "Bypass a single push with: git push --no-verify"
