#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
version="${1:-}"
firmware_mode="${SMART_SUP_RELEASE_FIRMWARE:-auto}"

if [[ -z "$version" ]]; then
  version="$(git -C "$repo_root" describe --tags --exact-match 2>/dev/null || true)"
fi
if [[ -z "$version" ]]; then
  version="$(date +%Y%m%d-%H%M%S)"
fi

dist_dir="$repo_root/dist"
mkdir -p "$dist_dir"

echo "Building Smart SUP release assets: $version"

if [[ "$firmware_mode" != "auto" && "$firmware_mode" != "always" && "$firmware_mode" != "never" ]]; then
  echo "Invalid SMART_SUP_RELEASE_FIRMWARE=$firmware_mode; expected auto, always, or never."
  exit 2
fi

previous_tag="$(git -C "$repo_root" describe --tags --abbrev=0 --exclude "$version" 2>/dev/null || true)"
firmware_changed="yes"
if [[ -n "$previous_tag" ]] &&
  git -C "$repo_root" diff --quiet "$previous_tag"..HEAD -- firmware/esp32 &&
  git -C "$repo_root" diff --quiet --cached -- firmware/esp32 &&
  git -C "$repo_root" diff --quiet -- firmware/esp32 &&
  [[ -z "$(git -C "$repo_root" ls-files --others --exclude-standard -- firmware/esp32)" ]]; then
  firmware_changed="no"
fi

build_firmware="yes"
case "$firmware_mode" in
  always)
    build_firmware="yes"
    ;;
  never)
    build_firmware="no"
    ;;
  auto)
    build_firmware="$firmware_changed"
    ;;
esac

pushd "$repo_root/android" >/dev/null
if [[ -n "${SMART_SUP_ANDROID_KEYSTORE:-}" ]]; then
  ./gradlew :app:assembleRelease
  apk_path="$repo_root/android/app/build/outputs/apk/release/app-release.apk"
else
  ./gradlew :app:assembleDebug
  apk_path="$repo_root/android/app/build/outputs/apk/debug/app-debug.apk"
fi
popd >/dev/null

apk_out="$dist_dir/smart-sup-controller-${version}.apk"
firmware_out="$dist_dir/smart-sup-esp32-firmware-${version}.bin"
manifest_out="$dist_dir/smart-sup-release-${version}.json"

rm -f "$firmware_out" "$manifest_out"
cp "$apk_path" "$apk_out"

min_app_version="${version#v}"

if [[ "$build_firmware" == "yes" ]]; then
  pushd "$repo_root/firmware/esp32" >/dev/null
  SMART_SUP_VERSION="$version" pio run -e lolin32_lite
  popd >/dev/null

  cp "$repo_root/firmware/esp32/.pio/build/lolin32_lite/firmware.bin" "$firmware_out"

  firmware_size="$(wc -c < "$firmware_out" | tr -d '[:space:]')"
  firmware_sha256="$(sha256sum "$firmware_out" | awk '{print $1}')"

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
fi

cat > "$dist_dir/smart-sup-release-${version}.txt" <<EOF
Smart SUP release assets
version: $version
apk: $(basename "$apk_out")
firmware: $([[ "$build_firmware" == "yes" ]] && basename "$firmware_out" || echo "skipped")
manifest: $([[ "$build_firmware" == "yes" ]] && basename "$manifest_out" || echo "skipped")
firmware mode: $firmware_mode
previous tag: ${previous_tag:-none}
firmware changed: $firmware_changed

Upload example:
tools/upload_release_assets.sh "$version"
EOF

echo "Created:"
echo "  $apk_out"
if [[ "$build_firmware" == "yes" ]]; then
  echo "  $firmware_out"
  echo "  $manifest_out"
else
  echo "  firmware skipped (mode=$firmware_mode, previous_tag=${previous_tag:-none}, firmware_changed=$firmware_changed)"
fi
echo "  $dist_dir/smart-sup-release-${version}.txt"
