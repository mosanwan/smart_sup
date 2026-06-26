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

if [[ ! -f "$apk" ]]; then
  echo "Missing APK in dist/. Run:"
  echo "  tools/build_release_assets.sh $version"
  exit 1
fi

if [[ -f "$firmware" && ! -f "$manifest" ]] || [[ ! -f "$firmware" && -f "$manifest" ]]; then
  echo "Firmware and manifest must be uploaded together."
  echo "  firmware: $firmware"
  echo "  manifest: $manifest"
  exit 1
fi

if ! command -v gh >/dev/null 2>&1; then
  echo "GitHub CLI 'gh' is not installed."
  exit 1
fi

gh release view "$version" --repo mosanwan/smart_sup >/dev/null 2>&1 || \
  gh release create "$version" --repo mosanwan/smart_sup --title "$version" --notes "Smart SUP $version"

assets=("$apk")
if [[ -f "$firmware" && -f "$manifest" ]]; then
  assets+=("$firmware" "$manifest")
fi

gh release upload "$version" "${assets[@]}" --repo mosanwan/smart_sup --clobber

echo "Uploaded release assets for $version"
if [[ ! -f "$firmware" ]]; then
  echo "Firmware skipped; this release only updates the Android APK."
fi
