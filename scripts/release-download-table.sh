#!/usr/bin/env bash
#
# release-download-table.sh
#
# Emits the "Downloads" section of a release body as markdown: one row per
# platform/architecture, with a link to this release's asset and a link that
# always resolves to the newest release.
#
# Two workflows publish release bodies and both need this table identical —
# release.yml writes the placeholder body, and release-notes.yml has Claude
# write the curated notes that later replace it. Generating it from one script
# is what keeps them from drifting apart, which is how the table ended up
# listing bare filenames with no links at all.
#
# The always-latest column matters on an old release page too: it is the answer
# to "I landed here from a search result, where is the current build?".
#
# Usage:
#   release-download-table.sh <version> [asset_repo]
#
# Example:
#   release-download-table.sh 9.4.0
#   release-download-table.sh 9.4.0 risa-labs-inc/BossConsole
#
# <version>     release version, without a leading "v"
# [asset_repo]  owner/name whose release assets to link, defaults to the
#               public releases repo. release.yml passes $GITHUB_REPOSITORY so
#               that sync-release.yml's private->public rewrite still applies.

set -euo pipefail

VERSION="${1:-}"
ASSET_REPO="${2:-risa-labs-inc/BossConsole-Releases}"

if [[ -z "$VERSION" ]]; then
  echo "Usage: $(basename "$0") <version> [asset_repo]" >&2
  exit 1
fi

if [[ ! "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+ ]]; then
  echo "Error: '$VERSION' is not a version. Pass it without a leading 'v'." >&2
  exit 1
fi

ASSET_URL="https://github.com/${ASSET_REPO}/releases/download/v${VERSION}"
LATEST_API="https://api.risaboss.com/functions/v1/latest-release?app=boss"

# platform | architecture | asset filename | download= | arch=
ROWS=(
  "**macOS**|Universal (Apple Silicon + Intel)|BOSS-${VERSION}-Universal.dmg|dmg|"
  "**Windows**|x64|BOSS-${VERSION}.msi|msi|"
  "**Windows**|ARM64|BOSS-${VERSION}-arm64.msi|msi|arm64"
  "**Linux DEB**|AMD64 (x86_64)|BOSS-${VERSION}-amd64.deb|deb|amd64"
  "**Linux DEB**|ARM64 (aarch64)|BOSS-${VERSION}-arm64.deb|deb|arm64"
  "**Linux RPM**|AMD64 (x86_64)|BOSS-${VERSION}-amd64.rpm|rpm|amd64"
  "**Linux RPM**|ARM64 (aarch64)|BOSS-${VERSION}-arm64.rpm|rpm|arm64"
  "**Linux JAR**|AMD64 (x86_64)|BOSS-${VERSION}-amd64.jar|jar|amd64"
  "**Linux JAR**|ARM64 (aarch64)|BOSS-${VERSION}-arm64.jar|jar|arm64"
)

echo "## 📦 Downloads"
echo ""
echo "| Platform | Architecture | This release | Always latest |"
echo "|----------|--------------|--------------|---------------|"

for row in "${ROWS[@]}"; do
  IFS='|' read -r platform arch asset pkg arch_param <<< "$row"

  latest="${LATEST_API}&download=${pkg}"
  if [[ -n "$arch_param" ]]; then
    latest="${latest}&arch=${arch_param}"
  fi

  # tr, not ${pkg^^} — macOS still ships bash 3.2, where that expansion is a
  # syntax error, and this script should run locally as well as in CI.
  label=$(printf '%s' "$pkg" | tr '[:lower:]' '[:upper:]')

  echo "| ${platform} | ${arch} | [\`${asset}\`](${ASSET_URL}/${asset}) | [${label}](${latest}) |"
done

echo ""
echo "The **Always latest** links resolve server-side to the newest release, so they stay"
echo "correct in a bookmark and need no API key. Release metadata — version, every asset,"
echo "sha256 checksums — is at [\`?app=boss\`](${LATEST_API})."
