#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK root}"
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to the installed NDK root}"
command -v go >/dev/null || { echo 'Go is required' >&2; exit 1; }
command -v gradle >/dev/null || { echo 'Gradle 8.9 is required' >&2; exit 1; }
command -v gomobile >/dev/null || { echo 'gomobile is required' >&2; exit 1; }
command -v gobind >/dev/null || { echo 'gobind is required' >&2; exit 1; }
# Newer gomobile versions require gobind in the project's module graph.
# This also downloads the application's Go dependencies and writes go.sum.
if ! grep -q 'golang.org/x/mobile/cmd/gobind' go.mod; then
  go get -tool golang.org/x/mobile/cmd/gobind@latest
fi
go mod tidy
mkdir -p app/libs
gomobile init
gomobile bind -target=android/arm64 -androidapi=26 -o app/libs/engine.aar ./engine
gradle --no-daemon :app:assembleDebug
bash scripts/export-apk.sh
