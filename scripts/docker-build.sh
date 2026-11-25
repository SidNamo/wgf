#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
bash scripts/build-android.sh

sha256sum "dist/WireGuard Forwarder.apk"
