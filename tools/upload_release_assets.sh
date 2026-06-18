#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
version="${1:-}"

if [[ -z "$version" ]]; then
  echo "Usage: tools/upload_release_assets.sh <version-or-tag>"
  exit 2
fi

apk="$repo_root/dist/smart-sup-controller-${version}.apk"
firmware="$repo_root/dist/smart-sup-esp32-firmware-${version}.bin"
manifest="$repo_root/dist/smart-sup-release-${version}.json"

if [[ ! -f "$apk" || ! -f "$firmware" || ! -f "$manifest" ]]; then
  echo "Missing release assets in dist/. Run:"
  echo "  tools/build_release_assets.sh $version"
  exit 1
fi

if ! command -v gh >/dev/null 2>&1; then
  echo "GitHub CLI 'gh' is not installed."
  exit 1
fi

gh release view "$version" --repo mosanwan/smart_sup >/dev/null 2>&1 || \
  gh release create "$version" --repo mosanwan/smart_sup --title "$version" --notes "Smart SUP $version"

gh release upload "$version" "$apk" "$firmware" "$manifest" --repo mosanwan/smart_sup --clobber

echo "Uploaded release assets for $version"
