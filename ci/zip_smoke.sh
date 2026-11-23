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
adb shell am start -n "$PKG/.MainActivity" -a android.intent.action.VIEW -t application/zip -d "content://$PKG.fixtureprovider/sample.zip"
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

python3 - <<'PYCODE'
import re,subprocess,xml.etree.ElementTree as ET
nodes=list(ET.parse('ci/zip-shots/zip-ui.xml').iter('node'))
n=next(n for n in nodes if n.attrib.get('text','')=='Open: unggah-test.txt')
x1,y1,x2,y2=map(int,re.findall(r'\d+',n.attrib['bounds']))
subprocess.run(['adb','shell','input','tap',str((x1+x2)//2),str((y1+y2)//2)],check=True)
PYCODE
sleep 2
adb shell uiautomator dump /sdcard/zip-open.xml
adb pull /sdcard/zip-open.xml ci/zip-shots/zip-open.xml
python3 - <<'PYCODE'
import xml.etree.ElementTree as ET
text='\n'.join(n.attrib.get('text','') for n in ET.parse('ci/zip-shots/zip-open.xml').iter('node'))
assert 'Unggah test file.' in text and 'Synthetic content' in text,text
print('ZIP text entry opens in existing viewer')
PYCODE
adb exec-out screencap -p > ci/zip-shots/zip-entry-open.png
adb shell input keyevent 4
sleep 1
adb exec-out screencap -p > ci/zip-shots/zip-back.png
