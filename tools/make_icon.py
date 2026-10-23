#!/usr/bin/env python3
"""Renders the Glassio icon (square) and TV banner (16:9): a frosted glass pane over soft colour."""
import sys
import numpy as np
from PIL import Image, ImageDraw, ImageFilter

def render(W, H, pane_w, pane_h, radius, out):
    S = 2  # supersample
    w, h = W * S, H * S
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    # Background: deep night gradient, a touch lighter at the top.
    t = yy / h
    bg = np.stack([10 + 10 * (1 - t), 11 + 10 * (1 - t), 20 + 18 * (1 - t)], -1)

    # Colour field behind the glass: three soft lights.
    def blob(cx, cy, r, col, a):
        d = np.sqrt((xx - cx * w) ** 2 + (yy - cy * h) ** 2) / (r * min(w, h))
        k = np.clip(1 - d, 0, 1) ** 2 * a
        return k[..., None] * np.array(col, np.float32)
    light = (blob(0.34, 0.40, 0.55, (94, 92, 230), 1.0)      # indigo
             + blob(0.70, 0.62, 0.50, (48, 176, 199), 0.95)  # teal
             + blob(0.58, 0.22, 0.38, (255, 55, 95), 0.55))  # pink
    scene = np.clip(bg + light * 0.85, 0, 255)
    base = Image.fromarray(scene.astype(np.uint8))

    pw, ph, r = int(pane_w * S), int(pane_h * S), int(radius * S)
    x0, y0 = (w - pw) // 2, (h - ph) // 2
    mask = Image.new("L", (w, h), 0)
    ImageDraw.Draw(mask).rounded_rectangle([x0, y0, x0 + pw, y0 + ph], r, fill=255)

    # Frosted glass: the scene heavily blurred, lifted and slightly brighter inside the pane.
    frost = base.filter(ImageFilter.GaussianBlur(28 * S))
    f = np.asarray(frost).astype(np.float32)
    f = f * 1.18 + 26
    # Soft vertical sheen: brighter top, calmer bottom.
    sheen = ((1 - (yy - y0) / ph).clip(0, 1) ** 1.6)[..., None] * 34
    f = np.clip(f + sheen, 0, 255)
    glass = Image.fromarray(f.astype(np.uint8))

    # Shadow under the pane.
    sh = Image.new("L", (w, h), 0)
    ImageDraw.Draw(sh).rounded_rectangle([x0, y0 + int(ph * 0.04), x0 + pw, y0 + ph + int(ph * 0.04)], r, fill=150)
    sh = sh.filter(ImageFilter.GaussianBlur(22 * S))
    out_img = Image.composite(Image.new("RGB", (w, h), (0, 0, 0)), base, sh.point(lambda v: int(v * 0.55)))
    out_img = Image.composite(glass, out_img, mask)

    # Rim: thin bright edge, strongest at the top-left (light from above), like tvOS glass.
    rim = Image.new("L", (w, h), 0)
    ImageDraw.Draw(rim).rounded_rectangle([x0, y0, x0 + pw, y0 + ph], r, outline=255, width=max(2, int(2.2 * S)))
    grad = np.clip(1.15 - ((xx - x0) / pw * 0.45 + (yy - y0) / ph * 0.85), 0.18, 1.0)
    rim_a = (np.asarray(rim).astype(np.float32) * grad * 0.75).astype(np.uint8)
    out_img = Image.composite(Image.new("RGB", (w, h), (255, 255, 255)), out_img, Image.fromarray(rim_a))

    out_img.resize((W, H), Image.LANCZOS).save(out, optimize=True)
    print("wrote", out)

d = sys.argv[1] if len(sys.argv) > 1 else "."
# Square app icon: pane in 16:10, centred.
render(512, 512, 330, 220, 46, f"{d}/icon_512.png")
# TV banner: wide pane.
render(1280, 720, 760, 420, 70, f"{d}/banner_1280x720.png")
