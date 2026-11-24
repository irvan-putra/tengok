#!/usr/bin/env bash
set -euo pipefail
PKG=com.vanipy.tengok
mkdir -p ci/format-shots
python3 ci/make_format_fixtures.py ci/format-fixtures
adb install -r app/build/outputs/apk/debug/*.apk
adb shell am start -n "$PKG/.MainActivity"
sleep 2
adb logcat -c || true
for f in sample.pdf sample.svg sample.docx sample.xlsx sample.pptx; do
  adb push "ci/format-fixtures/$f" "/data/local/tmp/$f"
  adb shell chmod 644 "/data/local/tmp/$f"
  adb shell run-as "$PKG" mkdir -p files
  adb shell run-as "$PKG" cp "/data/local/tmp/$f" "files/$f"
done
python3 ci/formats_smoke.py
