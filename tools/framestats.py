#!/usr/bin/env python3
"""Summarises `dumpsys gfxinfo <pkg> framestats` (read from stdin) per frame stage, in ms."""
import sys

lines = sys.stdin.read().splitlines()
hdr = None
rows = []
for ln in lines:
    if ln.startswith('Flags,'):
        hdr = ln.strip().strip(',').split(',')
        continue
    if hdr and ln and ln[0].isdigit():
        v = ln.strip().strip(',').split(',')
        if len(v) >= len(hdr) and v[0] == '0':
            rows.append(dict(zip(hdr, map(int, v[:len(hdr)]))))

def ms(a, b):
    vals = [(r[b] - r[a]) / 1e6 for r in rows if r.get(a, 0) > 0 and r.get(b, 0) >= r.get(a, 0)]
    if not vals:
        return '-'
    vals.sort()
    return f"med {vals[len(vals)//2]:6.1f}  max {vals[-1]:6.1f}"

print('frames', len(rows))
for name, a, b in [
    ('input+anim   ', 'HandleInputStart', 'PerformTraversalsStart'),
    ('layout       ', 'PerformTraversalsStart', 'DrawStart'),
    ('record draw  ', 'DrawStart', 'SyncQueued'),
    ('sync         ', 'SyncStart', 'IssueDrawCommandsStart'),
    ('issue (RT)   ', 'IssueDrawCommandsStart', 'SwapBuffers'),
    ('gpu after sub', 'SwapBuffers', 'GpuCompleted'),
    ('total        ', 'IntendedVsync', 'FrameCompleted'),
]:
    print(name, ms(a, b))
