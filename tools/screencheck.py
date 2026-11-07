#!/usr/bin/env python3
"""tools/screencheck.py [out-dir]: walks the Live TV screens on a real TV and checks each one drew.

Needs Pillow, adb and Live TV open on a channel (TV=<tv-ip>:5555, as tools/tvtest.sh). It puts a plain dark picture
behind the video (the test broadcast's stand-in: only in memory), opens each screen with sample data, and checks the
region where that screen's interface should be has something bright in it, that Live TV is still in front, and that
nothing crashed. Each screen's picture lands in out-dir, with a sheet of them all. Nothing is saved on the TV.

It proves the screens open and draw: not that they look right. That takes a look at the sheet.
"""
import os
import subprocess
import sys
import time

from PIL import Image

TV = os.environ.get('TV')
PACKAGE = 'com.glassioyahsun.tv'
OK, BACK, UP, DOWN, LEFT, RIGHT = 'DPAD_CENTER', 'BACK', 'DPAD_UP', 'DPAD_DOWN', 'DPAD_LEFT', 'DPAD_RIGHT'
BRIGHT = 170        # a pixel of interface, over the dark stand-in


def adb(*args, timeout=15):
    return subprocess.run(['adb', '-s', TV, *args], capture_output=True, timeout=timeout)


def shell(*args):
    return adb('shell', *args).stdout.decode('utf-8', 'replace')


def keys(*names):
    for name in names:
        shell('input', 'keyevent', name)
        time.sleep(0.55)


def hook(*args):
    shell('am', 'broadcast', '-a', 'com.glasslauncher.tv.live.TEST', '-p', PACKAGE, *args)


def bright_share(image, box):
    pixels = image.crop(box).convert('L').getdata()
    return sum(1 for p in pixels if p >= BRIGHT) / max(1, len(pixels))


def clear():
    """No controls up: Back would otherwise leave for the guide."""
    time.sleep(7)


# name, how to get there, how to leave, the box the interface draws in (1920 × 1080), the share of it that must be bright.
SCREENS = [
    ('player', lambda: keys(OK), lambda: clear(), (96, 640, 1824, 1010), 0.02),
    ('info', lambda: keys(OK, DOWN, OK), lambda: (keys(BACK, BACK), clear()), (96, 500, 1824, 1020), 0.02),
    ('channels', lambda: keys(OK, DOWN, RIGHT, OK), lambda: (keys(BACK, BACK), clear()), (96, 680, 1824, 1010), 0.02),
    ('audio', lambda: keys(OK, DOWN, RIGHT, RIGHT, OK), lambda: (keys(BACK, BACK), clear()), (1128, 72, 1848, 1008), 0.02),
    ('moments', lambda: hook('--ei', 'moments', '5'), lambda: (keys(BACK, BACK), clear()), (96, 700, 1824, 1020), 0.02),
    ('moments-guard', lambda: hook('--ei', 'moments', '5', '--ez', 'guarded', 'true'), lambda: (keys(BACK, BACK), clear()), (96, 700, 1824, 1020), 0.01),
    ('up-next', lambda: hook('--ez', 'upnext', 'true'), lambda: keys(BACK), (1224, 696, 1824, 1008), 0.02),
    ('settings', lambda: keys('PROG_BLUE'), lambda: (keys(BACK), time.sleep(1)), (96, 60, 1824, 1020), 0.02),
    ('guide', lambda: keys('PROG_RED'), lambda: (keys(BACK), time.sleep(2)), (40, 20, 1880, 1060), 0.01),
]


def main():
    if not TV:
        sys.exit('Set TV=<tv-ip>:5555')
    out = sys.argv[1] if len(sys.argv) > 1 else 'build/screencheck'
    os.makedirs(out, exist_ok=True)
    if 'live.Live' not in shell('dumpsys', 'activity', 'activities'):
        sys.exit('Open Live TV on a channel first')
    adb('logcat', '-c')
    hook('--ez', 'standin', 'true')
    time.sleep(1)
    failed, shots = [], []
    try:
        for name, enter, leave, box, share in SCREENS:
            enter()
            time.sleep(2.4)
            path = os.path.join(out, name + '.png')
            with open(path, 'wb') as f:
                f.write(adb('exec-out', 'screencap', '-p').stdout)
            image = Image.open(path).convert('RGB')
            found = bright_share(image, box)
            ok = found >= share
            print('%-14s %-5s %.1f%% bright (needs %.1f%%)' % (name, 'ok' if ok else 'FAIL', found * 100, share * 100))
            if not ok:
                failed.append(name)
            shots.append(image)
            leave()
    finally:
        hook('--ez', 'standin', 'false')
        hook('--ei', 'moments', '0')
    if 'live.Live' not in shell('dumpsys', 'activity', 'activities'):
        failed.append('left Live TV')
    log = adb('logcat', '-d', timeout=30).stdout.decode('utf-8', 'replace')
    if 'FATAL EXCEPTION' in log and PACKAGE in log:
        failed.append('a crash')
    cols, w, h = 3, 640, 360
    rows = (len(shots) + cols - 1) // cols
    sheet = Image.new('RGB', (cols * w, rows * h), (60, 60, 60))
    for i, image in enumerate(shots):
        sheet.paste(image.resize((w, h)), (i % cols * w, i // cols * h))
    sheet.save(os.path.join(out, 'sheet.png'))
    print('sheet: ' + os.path.join(out, 'sheet.png'))
    if failed:
        sys.exit('FAILED: ' + ', '.join(failed))
    print('all %d screens drew' % len(SCREENS))


if __name__ == '__main__':
    main()
