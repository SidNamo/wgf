#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
bash scripts/build-android.sh

mkdir -p dist
install -m 0644 app/build/outputs/apk/debug/app-debug.apk dist/WireGuardForwarder-debug.apk
printf '\nAPK output: %s/dist/WireGuardForwarder-debug.apk\n' "$PWD"
sha256sum dist/WireGuardForwarder-debug.apk
