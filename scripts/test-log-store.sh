#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/logstore-tests
javac -d build/logstore-tests \
  tests/android/content/Context.java \
  app/src/main/java/dev/wgforwarder/app/LogStore.java \
  tests/LogStoreTest.java
java -cp build/logstore-tests dev.wgforwarder.app.LogStoreTest
