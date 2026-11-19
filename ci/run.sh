#!/usr/bin/env bash
set -euo pipefail
PKG=com.vanipy.tengok
mkdir -p ci/shots
if [ -n "${RELEASE_TAG:-}" ]; then
  mkdir -p /tmp/rel
  gh release download "$RELEASE_TAG" --repo "$GITHUB_REPOSITORY" --dir /tmp/rel --pattern '*.apk' --pattern '*.sha256'
  (cd /tmp/rel && sha256sum -c ./*.sha256)
  adb install -r /tmp/rel/tengok-v*.apk
else
  adb install -r app/build/outputs/apk/debug/tengok-v*-debug.apk
fi
# independent fixture: a MIDI file this repo's Python writes, delivered through a real VIEW intent
python3 ci/make_midi.py /tmp/smoke.mid
adb push /tmp/smoke.mid /data/local/tmp/smoke.mid
adb shell chmod 644 /data/local/tmp/smoke.mid
adb shell run-as $PKG mkdir -p files
adb shell run-as $PKG cp /data/local/tmp/smoke.mid files/smoke.mid
adb shell run-as $PKG ls -la files/smoke.mid
python3 ci/smoke.py
