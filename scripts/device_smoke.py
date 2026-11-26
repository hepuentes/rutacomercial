"""Destructive ONLY to this app's test data on the named isolated emulator; never a phone.
Run after installing the debug APK. Produces a JSON evidence file in .tools/device-smoke.
"""
import json
import os
from pathlib import Path
import re
import sqlite3
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
ADB = ROOT / '.tools/android-sdk/platform-tools/adb.exe'
SERIAL = 'emulator-5556'
PKG = 'com.rutacomercial.app'
OUT = ROOT / '.tools/device-smoke'
OUT.mkdir(parents=True, exist_ok=True)
os.environ['ANDROID_USER_HOME'] = str(ROOT / '.android')
results = {}

def adb(*args, binary=False, check=True):
    p = subprocess.run([str(ADB), '-s', SERIAL, *args], capture_output=True, timeout=40)
    if check and p.returncode:
        raise RuntimeError(p.stderr.decode(errors='replace') or p.stdout.decode(errors='replace'))
    return p.stdout if binary else p.stdout.decode('utf-8', errors='replace').strip()

def launch():
    adb('shell', 'am', 'start', '-W', '-n', f'{PKG}/.MainActivity')
    time.sleep(2)

def tap(text, scroll=True):
    for attempt in range(7 if scroll else 1):
        adb('shell', 'uiautomator', 'dump', '/sdcard/rutacomercial-window.xml')
        xml = adb('shell', 'cat', '/sdcard/rutacomercial-window.xml')
        (OUT / 'last-window.xml').write_text(xml, encoding='utf-8')
        nodes = []
        for n in ET.fromstring(xml).iter('node'):
            if n.get('text') != text:
                continue
            coords = list(map(int, re.findall(r'\d+', n.get('bounds', ''))))
            if len(coords) == 4 and coords[2] > coords[0] and coords[3] > coords[1]:
                nodes.append(coords)
        if nodes:
            x1, y1, x2, y2 = nodes[-1]
            adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
            time.sleep(1)
            return
        if scroll:
            adb('shell', 'input', 'swipe', '540', '1750', '540', '650', '250')
    raise AssertionError(f'No se encontró el control visible: {text}')

def fix(lon, lat):
    adb('emu', 'geo', 'fix', str(lon), str(lat))
    # The provider requests 6 s but callback timing is not synchronized with ADB/NMEA.
    time.sleep(8)

def stop_and_snapshot(tag):
    adb('shell', 'am', 'force-stop', PKG)
    time.sleep(1)
    folder = OUT / tag
    folder.mkdir(exist_ok=True)
    available = adb('shell', 'run-as', PKG, 'ls', 'databases').splitlines()
    for name in ['rutacomercial.db', 'rutacomercial.db-wal', 'rutacomercial.db-shm']:
        if name in available:
            (folder / name).write_bytes(adb('exec-out', 'run-as', PKG, 'cat', f'databases/{name}', binary=True))
    con = sqlite3.connect(str(folder / 'rutacomercial.db'))
    con.row_factory = sqlite3.Row
    data = {table: [dict(row) for row in con.execute(f'SELECT * FROM {table}')] for table in ['journeys', 'track_points', 'incidents']}
    con.close()
    return data

def assert_that(key, condition, detail):
    results[key] = {'passed': bool(condition), 'detail': detail}
    if not condition:
        raise AssertionError(key + ': ' + str(detail))

try:
    assert adb('shell', 'getprop', 'ro.kernel.qemu') == '1', 'Solo se permite emulador'
    assert adb('shell', 'getprop', 'ro.boot.qemu.avd_name') == 'RutaComercialTest', 'Emulador diferente al aislado del proyecto'
    adb('shell', 'pm', 'clear', PKG)
    for permission in ['ACCESS_COARSE_LOCATION', 'ACCESS_FINE_LOCATION', 'POST_NOTIFICATIONS']:
        adb('shell', 'pm', 'grant', PKG, 'android.permission.' + permission)
    adb('shell', 'svc', 'wifi', 'enable')
    adb('shell', 'svc', 'data', 'enable')
    adb('shell', 'input', 'keyevent', '224')
    adb('shell', 'wm', 'dismiss-keyguard')
    launch()
    tap('Empezar recorrido')
    fix(-58.38160, -34.60370)
    fix(-58.38160, -34.60370)
    fix(-58.38160, -34.60370)
    fix(-58.38160, -34.60361)
    fix(-58.38160, -34.60355)
    screen_off_at = int(time.time()*1000)
    adb('shell', 'input', 'keyevent', '223')
    fix(-58.38160, -34.60346)
    fix(-58.38160, -34.60340)
    network_off_at = int(time.time()*1000)
    adb('shell', 'svc', 'wifi', 'disable')
    adb('shell', 'svc', 'data', 'disable')
    fix(-58.38160, -34.60331)
    fix(-58.38160, -34.60325)
    fix(-58.38160, -34.60316)
    fix(-58.38160, -34.60310)
    data = stop_and_snapshot('after-screen-off-and-no-network')
    points = data['track_points']
    assert_that('persisted_gps', len(points) >= 3, {'count': len(points)})
    assert_that('screen_off_recording_emulator', any(p['at'] >= screen_off_at for p in points), {'screen_off_at': screen_off_at})
    assert_that('no_internet_recording', any(p['at'] >= network_off_at for p in points), {'network_off_at': network_off_at})
    before_count = len(points)
    assert_that('crash_persists_active_state', data['journeys'][0]['state'] == 'ACTIVE', data['journeys'][0]['state'])
    adb('shell', 'input', 'keyevent', '224')
    adb('shell', 'wm', 'dismiss-keyguard')
    launch()
    recovered = stop_and_snapshot('after-process-recovery')
    assert_that('process_recovery_pauses', recovered['journeys'][0]['state'] == 'PAUSED', recovered['journeys'][0]['state'])
    assert_that('process_recovery_no_invented_points', len(recovered['track_points']) == before_count, {'before': before_count, 'after': len(recovered['track_points'])})
    assert_that('interruption_documented', any(i['kind'] == 'INTERRUPTION' for i in recovered['incidents']), {'incident_count': len(recovered['incidents'])})
    launch()
    tap('Mapa', scroll=False)
    tap('Reanudar')
    fix(-58.38150, -34.60330)
    fix(-58.38150, -34.60330)
    fix(-58.38150, -34.60330)
    adb('shell', 'pm', 'revoke', PKG, 'android.permission.ACCESS_FINE_LOCATION')
    time.sleep(2)
    launch()
    revoked = stop_and_snapshot('after-permission-revocation')
    assert_that('os_permission_revocation_pauses', revoked['journeys'][0]['state'] == 'PAUSED', revoked['journeys'][0]['state'])
    assert_that('resume_starts_new_segment', len({p['segment'] for p in revoked['track_points']}) >= 2, {'segments': sorted({p['segment'] for p in revoked['track_points']})})
    results['overall'] = 'PASSED'
except Exception as exc:
    results['overall'] = 'FAILED'
    results['error'] = str(exc)
    raise
finally:
    for service in ['wifi', 'data']:
        try: adb('shell', 'svc', service, 'enable')
        except Exception: pass
    try: adb('shell', 'input', 'keyevent', '224')
    except Exception: pass
    (OUT / 'results.json').write_text(json.dumps(results, indent=2, ensure_ascii=False), encoding='utf-8')
    print(json.dumps(results, indent=2, ensure_ascii=False))
