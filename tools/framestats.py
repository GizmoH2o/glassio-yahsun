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
    p90 = vals[int(len(vals) * 0.9) - 1] if len(vals) >= 10 else vals[-1]
    slow = sum(1 for x in vals if x > 33.4)
    return f"med {vals[len(vals)//2]:6.1f}  p90 {p90:6.1f}  max {vals[-1]:6.1f}  >33ms {slow}"

print('frames', len(rows))
for name, a, b in [
    ('vsync->start ', 'IntendedVsync', 'HandleInputStart'),
    ('input+anim   ', 'HandleInputStart', 'PerformTraversalsStart'),
    ('layout       ', 'PerformTraversalsStart', 'DrawStart'),
    ('record draw  ', 'DrawStart', 'SyncQueued'),
    ('queue->sync  ', 'SyncQueued', 'SyncStart'),
    ('sync         ', 'SyncStart', 'IssueDrawCommandsStart'),
    ('issue (RT)   ', 'IssueDrawCommandsStart', 'SwapBuffers'),
    ('gpu after sub', 'SwapBuffers', 'GpuCompleted'),
    ('gpu->done    ', 'GpuCompleted', 'FrameCompleted'),
    ('total        ', 'IntendedVsync', 'FrameCompleted'),
]:
    print(name, ms(a, b))
