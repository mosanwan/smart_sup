#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
version="${1:-}"

if [[ -z "$version" ]]; then
  version="$(git -C "$repo_root" describe --tags --exact-match 2>/dev/null || true)"
fi
if [[ -z "$version" ]]; then
  version="$(date +%Y%m%d-%H%M%S)"
fi

dist_dir="$repo_root/dist"
mkdir -p "$dist_dir"

echo "Building Smart SUP release assets: $version"

pushd "$repo_root/android" >/dev/null
if [[ -n "${SMART_SUP_ANDROID_KEYSTORE:-}" ]]; then
  ./gradlew :app:assembleRelease
  apk_path="$repo_root/android/app/build/outputs/apk/release/app-release.apk"
else
  ./gradlew :app:assembleDebug
  apk_path="$repo_root/android/app/build/outputs/apk/debug/app-debug.apk"
fi
popd >/dev/null

pushd "$repo_root/firmware/esp32" >/dev/null
SMART_SUP_VERSION="$version" pio run -e lolin32_lite
popd >/dev/null

apk_out="$dist_dir/smart-sup-controller-${version}.apk"
firmware_out="$dist_dir/smart-sup-esp32-firmware-${version}.bin"
manifest_out="$dist_dir/smart-sup-release-${version}.json"

cp "$apk_path" "$apk_out"
cp "$repo_root/firmware/esp32/.pio/build/lolin32_lite/firmware.bin" "$firmware_out"

firmware_size="$(wc -c < "$firmware_out" | tr -d '[:space:]')"
firmware_sha256="$(sha256sum "$firmware_out" | awk '{print $1}')"
min_app_version="${version#v}"

cat > "$manifest_out" <<EOF
{
  "version": "$version",
  "firmware": {
    "asset": "$(basename "$firmware_out")",
    "version": "$version",
    "board": "lolin32_lite",
    "size": $firmware_size,
    "sha256": "$firmware_sha256",
    "minAppVersion": "$min_app_version"
  }
}
EOF

cat > "$dist_dir/smart-sup-release-${version}.txt" <<EOF
Smart SUP release assets
version: $version
apk: $(basename "$apk_out")
firmware: $(basename "$firmware_out")
manifest: $(basename "$manifest_out")

Upload example:
gh release create "$version" "$apk_out" "$firmware_out" "$manifest_out" --repo mosanwan/smart_sup --title "$version" --notes "Smart SUP $version"
EOF

echo "Created:"
echo "  $apk_out"
echo "  $firmware_out"
echo "  $manifest_out"
echo "  $dist_dir/smart-sup-release-${version}.txt"
