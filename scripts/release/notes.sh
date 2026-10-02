#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
tag="${1:?Usage: notes.sh vVERSION}"
version=$(sed -n 's/^MARKETING_VERSION *= *//p' version.xcconfig)
python_version=$(sed -n 's/^version *= *"\(.*\)"$/\1/p' pyproject.toml)
[[ "$python_version" == "$version" ]] || { echo 'Python package version must match version.xcconfig' >&2; exit 1; }
[[ "$tag" == "v$version" ]] || { echo 'Release tag must match version.xcconfig' >&2; exit 1; }
awk -v version="$version" '
  /^## / { if (selected) exit; if ($0 ~ "^## \\[" version "\\]") selected=1; next }
  selected { print }
' CHANGELOG.md > release-notes.md
[[ -s release-notes.md ]] || { echo 'Missing CHANGELOG entry' >&2; exit 1; }
