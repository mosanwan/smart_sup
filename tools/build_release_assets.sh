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
pio run
popd >/dev/null

apk_out="$dist_dir/smart-sup-controller-${version}.apk"
firmware_out="$dist_dir/smart-sup-esp32-firmware-${version}.bin"

cp "$apk_path" "$apk_out"
cp "$repo_root/firmware/esp32/.pio/build/lolin32_lite/firmware.bin" "$firmware_out"

cat > "$dist_dir/smart-sup-release-${version}.txt" <<EOF
Smart SUP release assets
version: $version
apk: $(basename "$apk_out")
firmware: $(basename "$firmware_out")

Upload example:
gh release create "$version" "$apk_out" "$firmware_out" --repo mosanwan/smart_sup --title "$version" --notes "Smart SUP $version"
EOF

echo "Created:"
echo "  $apk_out"
echo "  $firmware_out"
echo "  $dist_dir/smart-sup-release-${version}.txt"
