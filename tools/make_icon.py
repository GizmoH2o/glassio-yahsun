#!/usr/bin/env python3
"""Renders the Glassio Yahsun icon (square) and TV banner (16:9): a frosted glass pane over soft colour, with the
Y monogram (icon) or the wordmark (banner) engraved on it in Inter, and the red dot of Live TV."""
import os
import sys
import numpy as np
from PIL import Image, ImageDraw, ImageFilter

FONTS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "res", "font")


def font(name, size):
    from PIL import ImageFont
    return ImageFont.truetype(os.path.join(FONTS, name), size)


def render(W, H, pane_w, pane_h, radius, out, mark=None):
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

    if mark:
        out_img = mark(out_img, S, (x0, y0, pw, ph))
    out_img.resize((W, H), Image.LANCZOS).save(out, optimize=True)
    print("wrote", out)

d = sys.argv[1] if len(sys.argv) > 1 else "."


def engrave(img, layer, glow):
    """White lettering on the glass: a soft glow under it, then the letters themselves."""
    halo = layer.filter(ImageFilter.GaussianBlur(glow))
    img = Image.composite(Image.new("RGB", img.size, (255, 255, 255)), img, halo.point(lambda v: int(v * 0.45)))
    return Image.composite(Image.new("RGB", img.size, (255, 255, 255)), img, layer)


def live_dot(img, cx, cy, r):
    """Live TV's red dot, with a little light around it."""
    glow = Image.new("L", img.size, 0)
    ImageDraw.Draw(glow).ellipse([cx - r * 2.2, cy - r * 2.2, cx + r * 2.2, cy + r * 2.2], fill=170)
    img = Image.composite(Image.new("RGB", img.size, (229, 50, 45)), img, glow.filter(ImageFilter.GaussianBlur(r * 1.2)))
    dot = Image.new("L", img.size, 0)
    ImageDraw.Draw(dot).ellipse([cx - r, cy - r, cx + r, cy + r], fill=255)
    return Image.composite(Image.new("RGB", img.size, (255, 72, 66)), img, dot)


def monogram(img, S, pane):
    """The icon: a fine Y centred on the pane, the red dot at its top right."""
    x0, y0, pw, ph = pane
    layer = Image.new("L", img.size, 0)
    f = font("inter_light.ttf", int(ph * 0.78))
    dr = ImageDraw.Draw(layer)
    l, t, r, b = dr.textbbox((0, 0), "Y", font=f)
    dr.text((x0 + (pw - (r - l)) / 2 - l, y0 + (ph - (b - t)) / 2 - t), "Y", font=f, fill=255)
    img = engrave(img, layer, 6 * S)
    return live_dot(img, x0 + pw - ph * 0.16, y0 + ph * 0.16, ph * 0.045)


def wordmark(img, S, pane):
    """The banner: Glassio, and YAHSUN in spaced capitals under it; the red dot beside YAHSUN."""
    x0, y0, pw, ph = pane
    layer = Image.new("L", img.size, 0)
    dr = ImageDraw.Draw(layer)
    big, small = font("inter_light.ttf", int(ph * 0.30)), font("inter_semibold.ttf", int(ph * 0.085))
    word = "Glassio"
    l, t, r, b = dr.textbbox((0, 0), word, font=big)
    sub_word, track = "YAHSUN", ph * 0.045
    widths = [dr.textbbox((0, 0), ch, font=small)[2] for ch in sub_word]
    sub_w = sum(widths) + track * (len(sub_word) - 1)
    sl, st, sr, sb = dr.textbbox((0, 0), sub_word, font=small)
    gap = ph * 0.07
    total = (b - t) + gap + (sb - st)
    top = y0 + (ph - total) / 2
    dr.text((x0 + (pw - (r - l)) / 2 - l, top - t), word, font=big, fill=255)
    sx, sy = x0 + (pw - sub_w) / 2, top + (b - t) + gap - st
    x = sx
    for ch, cw in zip(sub_word, widths):
        dr.text((x, sy), ch, font=small, fill=215)
        x += cw + track
    img = engrave(img, layer, 5 * S)
    return live_dot(img, sx + sub_w + ph * 0.1, sy + st + (sb - st) / 2, ph * 0.018)


# Square app icon: pane in 16:10, centred, the Y on it.
render(512, 512, 330, 220, 46, f"{d}/icon_512.png", monogram)
# TV banner: wide pane, the wordmark on it.
render(1280, 720, 760, 420, 70, f"{d}/banner_1280x720.png", wordmark)
