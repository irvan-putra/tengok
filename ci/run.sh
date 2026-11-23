#!/usr/bin/env bash
set -euo pipefail
PKG=com.vanipy.tengok
mkdir -p ci/shots
if [ -n "${RELEASE_TAG:-}" ]; then
  mkdir -p /tmp/rel
  gh release download "$RELEASE_TAG" --repo "$GITHUB_REPOSITORY" --dir /tmp/rel --pattern '*.apk' --pattern '*.sha256'
  (cd /tmp/rel && sha256sum -c ./*.sha256)
  if [ -n "${UPGRADE_FROM_TAG:-}" ]; then
    # upgrade proof: the previous release goes on first and creates real
    # user state; smoke.py then installs the candidate over it in place
    mkdir -p /tmp/rel-prev
    gh release download "$UPGRADE_FROM_TAG" --repo "$GITHUB_REPOSITORY" --dir /tmp/rel-prev --pattern '*.apk' --pattern '*.sha256'
    (cd /tmp/rel-prev && sha256sum -c ./*.sha256)
    adb install /tmp/rel-prev/tengok-v*.apk
    export UPGRADE_PROOF=1
  else
    adb install -r /tmp/rel/tengok-v*.apk
  fi
  # release bytes carry no debug fixture screen and run-as needs a debuggable
  # package: deliver the late-entrance fixture as a real user file instead
  python3 ci/make_latepad.py /tmp/latepad.mid
  adb shell mkdir -p /sdcard/Download/tengok-fixtures
  adb push /tmp/latepad.mid /sdcard/Download/tengok-fixtures/latepad.mid
  # real Bidik recordings (record-check CI captures) for the video proof
  adb push app/src/debug/assets/bidik-vfr-silent.mp4 /sdcard/Download/tengok-fixtures/bidik-vfr-silent.mp4
  adb push app/src/debug/assets/bidik-vfr-audio.mp4 /sdcard/Download/tengok-fixtures/bidik-vfr-audio.mp4
  python3 ci/smoke.py
  exit 0
fi
VER=$(sed -n 's/^val appVersionName = "\(.*\)"/\1/p' app/build.gradle.kts)
APK="app/build/outputs/apk/debug/tengok-v${VER}-debug.apk"
test -f "$APK"
adb install -r "$APK"
# independent fixture: a MIDI file this repo's Python writes, delivered through a real VIEW intent
python3 ci/make_midi.py /tmp/smoke.mid
adb push /tmp/smoke.mid /data/local/tmp/smoke.mid
adb shell chmod 644 /data/local/tmp/smoke.mid
adb shell run-as $PKG mkdir -p files
adb shell run-as $PKG cp /data/local/tmp/smoke.mid files/smoke.mid
adb shell run-as $PKG ls -la files/smoke.mid
python3 ci/smoke.py
