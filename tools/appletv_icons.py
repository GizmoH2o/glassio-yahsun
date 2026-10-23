#!/usr/bin/env python3
"""
Builds an Apple TV icon library for Glass Launcher.

For each app name it asks the public iTunes Search API for the tvOS version of the app and downloads its
Apple TV home-screen icon (the flattened .lsr image stack, 1280x768 - the largest size Apple serves).
From that it renders three more looks:
  dark  - the logo on near-black   (tvOS dark appearance)
  white - the logo on white        (light appearance)
  clear - the logo alone in white on a transparent background (clear / glass appearance; the launcher's
          glass shows through)
dark/white/clear are only made when the icon has a flat background the logo can be lifted off.

Output: <out>/<variant>/<App Name>.png   and   <out>/index.json
"""
import json, os, re, sys, time, urllib.parse, urllib.request
from io import BytesIO
from PIL import Image, ImageFilter
import socket
# Python's urllib has no "happy eyeballs": an unreachable IPv6 route stalls every call until the timeout.
_gai = socket.getaddrinfo
socket.getaddrinfo = lambda host, *a, **k: [r for r in _gai(host, *a, **k) if r[0] == socket.AF_INET] or _gai(host, *a, **k)

OUT = sys.argv[1] if len(sys.argv) > 1 else "appletv-icons"
APPS = [
    # global streaming
    "Netflix", "YouTube", "Disney+", "Prime Video", "Apple TV", "HBO Max", "Hulu", "Paramount+", "Peacock TV",
    "Crunchyroll", "Tubi", "Pluto TV", "Plex", "Infuse 8", "VLC media player", "Kodi", "Emby", "Jellyfin",
    "Twitch", "YouTube TV", "Sling TV", "Fubo", "Philo", "DIRECTV", "Spectrum TV", "Xfinity Stream",
    "Starz", "MGM+", "AMC+", "BritBox", "Acorn TV", "Shudder", "Mubi", "Criterion Channel", "Discovery+",
    "ESPN", "DAZN", "NFL", "NBA", "MLB", "F1 TV", "Red Bull TV", "Fox Nation", "CBS News", "BBC iPlayer",
    "ITVX", "Channel 4", "Now TV", "Sky Go", "Canal+", "RTL+", "Joyn", "Rakuten TV", "Viaplay",
    "Kanopy", "Hoopla", "PBS", "Nebula", "CuriosityStream", "Crackle", "Vudu", "Fandango at Home",
    "Pluto", "Roku Channel", "Freevee", "Hallmark+", "BET+", "ALLBLK", "Hayu", "Funimation", "HIDIVE",
    "Viki", "iQIYI", "WeTV", "Viu", "Zee5", "JioHotstar", "SonyLIV", "Eros Now",
    # Arabic / Middle East
    "Shahid", "OSN+", "STARZPLAY", "TOD", "beIN CONNECT", "Yango Play", "Weyyak", "WATCH IT", "Anghami",
    "Al Jazeera", "Al Arabiya", "Sky News Arabia", "Rotana", "MBC", "Jawwy TV", "TOD TV", "Stc tv",
    # music / audio
    "Apple Music", "Spotify", "Amazon Music", "YouTube Music", "Tidal", "Deezer", "SoundCloud", "Pandora",
    "iHeartRadio", "TuneIn Radio", "Apple Podcasts", "Pocket Casts",
    # Apple & utilities
    "Apple Arcade", "Apple Fitness", "TV Remote", "Photos", "Settings", "App Store", "Search", "Computers",
    "FaceTime", "Home", "Weather", "Aerial Screensaver",
    # sports / news / misc
    "Bloomberg", "CNN", "Fox Sports", "NBC Sports", "Paramount Network", "TED", "Vimeo", "Dailymotion",
    "Facebook Watch", "Steam Link", "Moonlight", "GeForce NOW", "Xbox Cloud Gaming", "Pinterest",
    "Fitness+", "Peloton", "Zwift", "Calm", "Headspace", "Smart IPTV", "IPTVX", "UHF - Love your IPTV",
    "Channels", "TiviMate", "SmartTube", "Stremio", "Downcast", "Overcast",
]
COUNTRIES = ["us", "ae"]

_last = [0.0]
def get(url, timeout=15, api=True):
    """iTunes allows ~20 calls a minute: pace the calls and back off when it says no."""
    for attempt in range(4):
        if api:
            wait = 3.1 - (time.time() - _last[0])
            if wait > 0: time.sleep(wait)
            _last[0] = time.time()
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 GlassLauncher/1.6"})
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return r.read()
        except Exception as e:
            print("    retry", attempt + 1, str(e)[:60]); time.sleep(20 * (attempt + 1))
    raise IOError("gave up")

def norm(s):
    return re.sub(r"[^a-z0-9؀-ۿ]+", "", s.lower())

def find(name):
    for cc in COUNTRIES:
        q = urllib.parse.urlencode({"term": name, "entity": "software", "country": cc, "limit": 25})
        try:
            res = json.loads(get("https://itunes.apple.com/search?" + q)).get("results", [])
        except Exception:
            time.sleep(3); continue
        if not res: continue
        # Search only returns iPhone artwork; a lookup with entity=tvSoftware returns the Apple TV icon.
        ids = ",".join(str(r["trackId"]) for r in res[:8])
        try:
            tvres = json.loads(get(f"https://itunes.apple.com/lookup?id={ids}&entity=tvSoftware&country={cc}")).get("results", [])
        except Exception:
            time.sleep(3); continue
        tv = [r for r in tvres if ".lsr/" in r.get("artworkUrl512", "")]
        if not tv: continue
        n = norm(name)
        tv.sort(key=lambda r: (0 if norm(r["trackName"]).startswith(n) or n in norm(r["trackName"]) else 1,
                               -r.get("userRatingCount", 0)))
        return tv[0]
    return None

import numpy as np

def lift(im):
    """Alpha mask of the logo, or None. Flat backgrounds are keyed out; on a gradient or photo
    background only a white logo can be lifted."""
    a = np.asarray(im.convert("RGB")).astype(np.int16)
    h, w, _ = a.shape
    border = np.concatenate([a[:3].reshape(-1, 3), a[-3:].reshape(-1, 3), a[:, :3].reshape(-1, 3), a[:, -3:].reshape(-1, 3)])
    bg = np.median(border, axis=0)
    flat = (np.abs(border - bg).sum(1) < 24).mean()
    if flat >= 0.9:
        d = np.abs(a - bg).sum(2)
        m = np.clip((d - 18) * 255 / 72, 0, 255)
    else:
        lum = a.mean(2); sat = a.max(2) - a.min(2)
        white = (lum > 205) & (sat < 45)
        if white[:4].mean() > 0.2 or white[-4:].mean() > 0.2: return None
        m = np.clip((lum - 185) * 255 / 50, 0, 255) * (sat < 60)
    mask = Image.fromarray(m.astype(np.uint8)).filter(ImageFilter.GaussianBlur(0.6))
    cover = (np.asarray(mask) > 128).mean()
    if cover < 0.008 or cover > 0.6: return None
    return mask

def recolor(fg, dark_bg):
    """Neutral parts of the logo that would vanish on the new background flip (black <-> white);
    coloured parts keep their colour, like tvOS does."""
    a = np.asarray(fg).astype(np.int16).copy()
    rgb = a[..., :3]; lum = rgb.mean(2); sat = rgb.max(2) - rgb.min(2)
    if dark_bg: sel = (lum < 90) & (sat < 50)
    else: sel = (lum > 190) & (sat < 50)
    rgb[sel] = 255 - rgb[sel]
    return Image.fromarray(a.astype(np.uint8))

def variants(im):
    out = {"default": im.convert("RGBA")}
    mask = lift(im)
    if mask is None: return out
    fg = im.convert("RGBA"); fg.putalpha(mask)
    size = im.size
    dark = Image.new("RGBA", size, (18, 18, 20, 255)); dark.alpha_composite(recolor(fg, True))
    white = Image.new("RGBA", size, (255, 255, 255, 255)); white.alpha_composite(recolor(fg, False))
    clear = Image.new("RGBA", size, (255, 255, 255, 255)); clear.putalpha(mask)
    out.update(dark=dark, white=white, clear=clear)
    return out

def safe(name): return re.sub(r'[\\/:*?"<>|]+', " ", name).strip()

def main():
    for v in ("default", "dark", "white", "clear"): os.makedirs(os.path.join(OUT, v), exist_ok=True)
    idx = os.path.join(OUT, "index.json")
    index = json.load(open(idx)) if os.path.exists(idx) else []
    seen = set()
    for name in APPS:
        done = os.path.join(OUT, "done.txt")
        if os.path.exists(done) and name in open(done).read().splitlines(): continue
        r = find(name)
        open(done, "a").write(name + "\n")
        if not r: print("  -", name, "(no Apple TV app)"); continue
        title = re.split(r":| - | – | \| ", r["trackName"])[0].strip()
        if r["trackId"] in seen: continue
        seen.add(r["trackId"])
        url = re.sub(r"/\d+x\d+bb\.\w+$", "/1280x768bb.png", r["artworkUrl512"])
        try: im = Image.open(BytesIO(get(url, api=False)))
        except Exception as e: print("  !", name, e); continue
        vs = variants(im)
        for v, img in vs.items():
            img.save(os.path.join(OUT, v, safe(title) + ".png"), optimize=True)
        index.append({"name": title, "bundle": r.get("bundleId"), "variants": sorted(vs)})
        json.dump(index, open(idx, "w"), ensure_ascii=False, indent=1)
        print("  +", title, "/".join(sorted(vs)))
    json.dump(index, open(os.path.join(OUT, "index.json"), "w"), ensure_ascii=False, indent=1)
    print(len(index), "apps")

main()
