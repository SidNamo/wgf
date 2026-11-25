#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/backup-codec-tests
javac -d build/backup-codec-tests \
  app/src/main/java/dev/wgforwarder/app/BackupCodec.java \
  tests/BackupCodecTest.java
java -cp build/backup-codec-tests dev.wgforwarder.app.BackupCodecTest
