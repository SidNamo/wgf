#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

mkdir -p dist
install -m 0644 app/build/outputs/apk/debug/app-debug.apk "dist/WireGuard Forwarder.apk"
printf 'APK output: %s/dist/WireGuard Forwarder.apk\n' "$PWD"
