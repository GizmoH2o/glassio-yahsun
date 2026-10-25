import sys
lines = sys.stdin.read().splitlines()
hdr = None
rows = []
for l in lines:
    if l.startswith("Flags,"):
        hdr = l.split(",")
        continue
    if hdr and l and l[0].isdigit():
        p = l.split(",")
        if len(p) >= len(hdr) - 1:
            rows.append(dict(zip(hdr, p)))
t0 = None
for r in rows:
    if r.get("Flags") not in ("0",):
        continue
    iv = int(r["IntendedVsync"]); fc = int(r["FrameCompleted"])
    gpu = int(r.get("GpuCompleted", "0") or 0)
    sub = int(r.get("SwapBuffers", "0") or 0)
    if t0 is None:
        t0 = iv
    print("t=%6.0f total=%5.1f gpu=%5.1f" % ((iv - t0) / 1e6, (fc - iv) / 1e6, (gpu - sub) / 1e6 if gpu and sub else -1))
