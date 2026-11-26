"""Cold ACTION_SEND integration on RutaComercialTest ONLY. Clears that emulator's app data."""
import json
import math
import os
from pathlib import Path
import re
import sqlite3
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
ADB = ROOT / '.tools/android-sdk/platform-tools/adb.exe'
OUT = ROOT / '.tools/share-smoke'
OUT.mkdir(exist_ok=True)
PKG = 'com.rutacomercial.app'
os.environ['ANDROID_USER_HOME'] = str(ROOT / '.android')


def adb(*args, binary=False):
    result = subprocess.run([str(ADB), '-s', 'emulator-5556', *args], capture_output=True, timeout=40)
    if result.returncode:
        raise RuntimeError(result.stderr.decode(errors='replace'))
    return result.stdout if binary else result.stdout.decode('utf-8', errors='replace').strip()


def node(text):
    adb('shell', 'uiautomator', 'dump', '/sdcard/rc-share-test.xml')
    xml = adb('shell', 'cat', '/sdcard/rc-share-test.xml')
    (OUT / 'last-window.xml').write_text(xml, encoding='utf-8')
    return next((n for n in ET.fromstring(xml).iter('node') if n.get('text') == text and n.get('enabled') == 'true'), None)


def wait_node(text):
    for _ in range(6):
        n = node(text)
        if n is not None:
            return n
        time.sleep(1)
    raise AssertionError('Missing control: ' + text)


def tap(text):
    n = wait_node(text)
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', n.get('bounds')))
    x, y = str((x1+x2)//2), str((y1+y2)//2)
    adb('shell', 'input', 'touchscreen', 'swipe', x, y, x, y, '150')
    time.sleep(1)


def capture(name):
    adb('shell', 'screencap', '-p', '/sdcard/rc-share-test.png')
    adb('pull', '/sdcard/rc-share-test.png', str(ROOT / 'docs/capturas' / name))


assert adb('shell', 'getprop', 'ro.kernel.qemu') == '1'
assert adb('shell', 'getprop', 'ro.boot.qemu.avd_name') == 'RutaComercialTest'
adb('shell', 'pm', 'clear', PKG)
adb('shell', 'input', 'keyevent', '224')
adb('shell', 'wm', 'dismiss-keyguard')
# Package-only resolution exercises the manifest's share filter, not an explicit activity launch.
adb('shell', 'am', 'start', '-W', '-a', 'android.intent.action.SEND', '-t', 'text/plain', '-p', PKG,
    '--es', 'android.intent.extra.TEXT', 'https://www.google.com/maps?q=-32.89,-68.84')
wait_node('Delimitar zona aquí')
capture('ubicacion-compartida-0.3.0.png')
tap('Delimitar zona aquí')
wait_node('Usar esta zona')
time.sleep(1)
capture('zona-desde-google-0.3.0.png')
tap('Usar esta zona')
wait_node('Opciones')
adb('shell', 'am', 'force-stop', PKG)
for name in adb('shell', 'run-as', PKG, 'ls', 'databases').splitlines():
    if name in ('rutacomercial.db', 'rutacomercial.db-wal', 'rutacomercial.db-shm'):
        (OUT / name).write_bytes(adb('exec-out', 'run-as', PKG, 'cat', 'databases/' + name, binary=True))
with sqlite3.connect(OUT / 'rutacomercial.db') as db:
    zones = db.execute('SELECT polygonJson FROM sectors').fetchall()
    assert len(zones) == 1
    points = json.loads(zones[0][0])
    assert len(points) == 4
    lat = sum(p[0] for p in points)/4
    lon = sum(p[1] for p in points)/4
    distance = math.hypot((lat + 32.89)*111195, (lon + 68.84)*111195*math.cos(math.radians(lat)))
    assert distance < 100, distance
    counts = {t: db.execute('SELECT COUNT(*) FROM ' + t).fetchone()[0] for t in ('journeys', 'track_points')}
    assert all(v == 0 for v in counts.values()), counts
result = {'overall': 'PASSED', 'cold_action_send_manifest': True, 'review_before_save': True,
          'saved_zones': len(zones), 'distance_to_shared_pin_m': round(distance, 2), 'records': counts}
(OUT / 'results.json').write_text(json.dumps(result, indent=2, ensure_ascii=False), encoding='utf-8')
print(json.dumps(result, indent=2, ensure_ascii=False))
