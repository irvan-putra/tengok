import subprocess,time,re,xml.etree.ElementTree as ET

def adb(*args):
    return subprocess.run(['adb',*args],capture_output=True,check=True).stdout

def nodes():
    for i in range(8):
        try:
            adb('shell','uiautomator','dump','/sdcard/zv.xml')
            return list(ET.fromstring(adb('shell','cat','/sdcard/zv.xml')).iter('node'))
        except Exception: time.sleep(2)
    raise RuntimeError('UI unavailable')

def tap(label):
    for i in range(8):
        ns=nodes()
        n=next((n for n in ns if n.attrib.get('text','')==label),None)
        if n is not None:
            a,b,c,d=map(int,re.findall(r'\d+',n.attrib['bounds']));adb('shell','input','tap',str((a+c)//2),str((b+d)//2));return
        time.sleep(1)
    raise AssertionError(label)

def need(fragment):
    for i in range(8):
        text='\n'.join(n.attrib.get('text','') for n in nodes())
        if fragment in text:return
        time.sleep(1)
    raise AssertionError(fragment+' missing: '+text)

def screenshot(name):
    open('ci/zip-shots/'+name+'.png','wb').write(adb('exec-out','screencap','-p'))

time.sleep(2)
for name,expected in [('pixel.png','480'),('tone.wav','Play'),('twotrack.mid','MIDI format')]:
    need('ZIP contents')
    tap('Open: '+name)
    time.sleep(2)
    # PNG web fixture is 96 x 64. Dimensions are shown in image panel.
    if name=='pixel.png': need('64')
    else: need(expected)
    if name in ['tone.wav','twotrack.mid']:
        tap('Play');time.sleep(1);screenshot('zip-'+name+'-playing')
        if name=='twotrack.mid':
            log=adb('logcat','-d','-s','tengok:I').decode('utf-8','replace')
            assert 'TENGOK-MIDI-PREPARED' in log,log
    else:screenshot('zip-'+name)
    adb('shell','input','keyevent','4');time.sleep(1)
need('ZIP contents')
print('ZIP image, audio and MIDI viewers opened in memory; back returns to archive')
