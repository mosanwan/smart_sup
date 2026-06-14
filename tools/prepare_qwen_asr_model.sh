#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODEL_NAME="sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25"
MODEL_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/${MODEL_NAME}.tar.bz2"
MODEL_CACHE_DIR="${ROOT_DIR}/ref/models"
ARCHIVE_PATH="${MODEL_CACHE_DIR}/${MODEL_NAME}.tar.bz2"
MODEL_DIR="${MODEL_CACHE_DIR}/${MODEL_NAME}"
ANDROID_PACKAGE="com.smartsup.controller"
DEVICE_MODEL_PARENT="files/models"

mkdir -p "${MODEL_CACHE_DIR}"

if [[ ! -d "${MODEL_DIR}" ]]; then
  if [[ ! -f "${ARCHIVE_PATH}" ]]; then
    echo "Downloading ${MODEL_NAME}..."
    curl -L --fail -o "${ARCHIVE_PATH}" "${MODEL_URL}"
  fi

  echo "Extracting ${MODEL_NAME}..."
  tar -xjf "${ARCHIVE_PATH}" -C "${MODEL_CACHE_DIR}"
fi

for path in \
  "${MODEL_DIR}/conv_frontend.onnx" \
  "${MODEL_DIR}/encoder.int8.onnx" \
  "${MODEL_DIR}/decoder.int8.onnx" \
  "${MODEL_DIR}/tokenizer/merges.txt" \
  "${MODEL_DIR}/tokenizer/tokenizer_config.json" \
  "${MODEL_DIR}/tokenizer/vocab.json"; do
  if [[ ! -f "${path}" ]]; then
    echo "Missing required model file: ${path}" >&2
    exit 1
  fi
done

echo "Model is ready at ${MODEL_DIR}"

if command -v adb >/dev/null 2>&1 && adb devices | awk 'NR > 1 && $2 == "device" { found = 1 } END { exit found ? 0 : 1 }'; then
  if ! adb shell "run-as ${ANDROID_PACKAGE} true" >/dev/null 2>&1; then
    echo "Connected Android device detected, but ${ANDROID_PACKAGE} is not installed or is not debuggable." >&2
    echo "Install the debug app first, then run this script again." >&2
    exit 1
  fi

  echo "Connected Android device detected. Importing model into app internal files..."
  adb shell "run-as ${ANDROID_PACKAGE} sh -c 'mkdir -p ${DEVICE_MODEL_PARENT}'"
  tar -cf - -C "${MODEL_CACHE_DIR}" "${MODEL_NAME}" \
    | adb exec-in run-as "${ANDROID_PACKAGE}" sh -c "tar -xf - -C ${DEVICE_MODEL_PARENT}"
  echo "Imported to ${ANDROID_PACKAGE}:/${DEVICE_MODEL_PARENT}/${MODEL_NAME}"
else
  echo "No connected Android device detected. Run this script again after connecting the phone to push the model."
fi
