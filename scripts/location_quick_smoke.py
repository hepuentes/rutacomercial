"""Interactive smoke fixture: touches ONLY the named isolated Android emulator, never a phone.
Run with --prepare to reset the test app, turn location off and capture its native enable prompt.
Run with --capture after enabling location to save the current screen and database evidence.
"""
from pathlib import Path
import json
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
ADB = ROOT / '.tools/android-sdk/platform-tools/adb.exe'
OUT = ROOT / '.tools/location-quick-smoke'
OUT.mkdir(parents=True, exist_ok=True)
os.environ['ANDROID_USER_HOME'] = str(ROOT / '.android')
PKG = 'com.rutacomercial.app'

def adb(*args, binary=False):
    result = subprocess.run([str(ADB), '-s', 'emulator-5556', *args], capture_output=True, timeout=45, check=True)
    return result.stdout if binary else result.stdout.decode('utf-8', errors='replace').strip()

def tree():
    adb('shell', 'uiautomator', 'dump', '/sdcard/rc-location.xml')
    xml = adb('shell', 'cat', '/sdcard/rc-location.xml')
    return xml, ET.fromstring(xml)

def tap(label):
    _, root = tree()
    nodes = [n for n in root.iter('node') if n.attrib.get('text') == label]
    if not nodes:
        raise RuntimeError('No se encontró: ' + label)
    x1,y1,x2,y2 = map(int, re.findall(r'\d+',nodes[0].attrib['bounds']))
    adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))

assert adb('shell','getprop','ro.boot.qemu.avd_name') == 'RutaComercialTest', 'No es el emulador aislado'
mode = sys.argv[1] if len(sys.argv)>1 else '--capture'
if mode == '--prepare':
    adb('shell','pm','clear',PKG)
    for permission in ['ACCESS_FINE_LOCATION','ACCESS_COARSE_LOCATION','POST_NOTIFICATIONS']:
        adb('shell','pm','grant',PKG,'android.permission.'+permission)
    adb('shell','cmd','location','set-location-enabled','false')
    adb('shell','am','start','-W','-n',PKG+'/.MainActivity')
    time.sleep(3)
    xml,_ = tree()
    assert 'Mi ubicación' in xml
    (OUT/'opened-with-location-off.xml').write_text(xml,encoding='utf-8')
    tap('Mi ubicación')
    time.sleep(4)
    xml,root = tree()
    packages = sorted({n.attrib.get('package') for n in root.iter('node')})
    assert any(p in ['com.android.settings','com.google.android.gms'] for p in packages), packages
    (OUT/'enable-prompt.xml').write_text(xml,encoding='utf-8')
    (OUT/'enable-prompt.png').write_bytes(adb('exec-out','screencap','-p',binary=True))
    print(json.dumps({'openedWithoutPrompt':True,'nativeActivationPromptShown':True,'packages':packages}))
elif mode == '--capture':
    xml,_ = tree()
    (OUT/'current.xml').write_text(xml,encoding='utf-8')
    (OUT/'current.png').write_bytes(adb('exec-out','screencap','-p',binary=True))
    print(xml)
else:
    raise ValueError(mode)
