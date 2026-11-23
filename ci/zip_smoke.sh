#!/usr/bin/env bash
set -euo pipefail
PKG=com.vanipy.tengok
mkdir -p ci/zip-shots
adb install -r app/build/outputs/apk/debug/*.apk
adb shell am start -n "$PKG/.MainActivity"
adb push docs/fixtures/sample.zip /data/local/tmp/sample.zip
adb shell chmod 644 /data/local/tmp/sample.zip
adb shell run-as "$PKG" mkdir -p files
adb shell run-as "$PKG" cp /data/local/tmp/sample.zip files/sample.zip
adb shell am start -n "$PKG/.ViewerActivity" -a android.intent.action.VIEW -t application/zip --es fixture_path "/data/user/0/$PKG/files/sample.zip"
sleep 3
adb shell uiautomator dump /sdcard/zip-ui.xml
adb pull /sdcard/zip-ui.xml ci/zip-shots/zip-ui.xml
python3 - <<'PY'
import xml.etree.ElementTree as ET
nodes = list(ET.parse('ci/zip-shots/zip-ui.xml').iter('node'))
text = '\n'.join(n.attrib.get('text','') for n in nodes)
assert 'ZIP contents' in text and 'unggah-test.txt' in text and '53 bytes' in text, text
print('ZIP viewer passed: sample file, entry count and sizes visible')
PY
adb exec-out screencap -p > ci/zip-shots/zip-contents.png
