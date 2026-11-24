import re
import subprocess
import time
import xml.etree.ElementTree as ET

PKG = 'com.vanipy.tengok'
DOCX = 'application/vnd.openxmlformats-officedocument.wordprocessingml.document'
XLSX = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
PPTX = 'application/vnd.openxmlformats-officedocument.presentationml.presentation'


def adb(*args):
    return subprocess.run(['adb', *args], capture_output=True, check=True).stdout


def nodes():
    for _ in range(8):
        try:
            adb('shell', 'uiautomator', 'dump', '/sdcard/fm.xml')
            return list(ET.fromstring(adb('shell', 'cat', '/sdcard/fm.xml')).iter('node'))
        except Exception:
            time.sleep(2)
    raise RuntimeError('UI unavailable')


def screen_text():
    return '\n'.join(n.attrib.get('text', '') + ' ' + n.attrib.get('content-desc', '') for n in nodes())


def need(fragment):
    text = ''
    for _ in range(10):
        text = screen_text()
        if fragment.lower() in text.lower():
            return
        time.sleep(1)
    raise AssertionError(fragment + ' missing: ' + text)


def tap(label):
    for _ in range(8):
        n = next((n for n in nodes() if n.attrib.get('text', '').lower() == label.lower()), None)
        if n is not None:
            a, b, c, d = map(int, re.findall(r'\d+', n.attrib['bounds']))
            adb('shell', 'input', 'tap', str((a + c) // 2), str((b + d) // 2))
            return
        time.sleep(1)
    raise AssertionError('no button ' + label)


def shot(name):
    open('ci/format-shots/' + name + '.png', 'wb').write(adb('exec-out', 'screencap', '-p'))


def view(name, mime):
    adb('shell', 'am', 'start', '-n', PKG + '/.MainActivity', '-a', 'android.intent.action.VIEW',
        '-t', mime, '-d', 'content://' + PKG + '.fixtureprovider/' + name)
    time.sleep(3)


view('sample.pdf', 'application/pdf')
need('Page 1 of 2')
time.sleep(2)
shot('pdf-page1')
tap('Next ›')
need('Page 2 of 2')
time.sleep(2)
shot('pdf-page2')
tap('+')
need('150%')
time.sleep(2)
shot('pdf-page2-zoom')
print('PDF: two pages, next and zoom work')

view('sample.svg', 'image/svg+xml')
need('SVG preview')
need('CSS styles')
shot('svg')
tap('Show SVG source')
need('Tengok SVG fixture')
print('SVG: preview drawn, unsupported CSS reported, source shown')

view('sample.docx', DOCX)
need('Word document')
need('Tengok Word fixture')
need('Second paragraph with')
shot('docx')
tap('Show ZIP contents')
need('ZIP contents')
print('DOCX: text shown, ZIP contents reachable')

view('sample.xlsx', XLSX)
need('Excel workbook')
need('Budget')
need('Tengok Excel fixture')
need('Rice')
shot('xlsx')
print('XLSX: sheet name and cell values shown')

view('sample.pptx', PPTX)
need('PowerPoint presentation')
need('Tengok PowerPoint fixture')
need('Second slide text')
shot('pptx')
print('PPTX: slide text shown in order')

log = adb('logcat', '-d', '-s', 'AndroidRuntime:E').decode('utf-8', 'replace')
assert 'FATAL EXCEPTION' not in log, log
print('FORMATS SMOKE OK')
