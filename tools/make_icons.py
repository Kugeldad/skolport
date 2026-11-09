"""Generate Skolport launcher icons: an arched gateway ("port") with an open book and a star.

Writes, for each density:
  mipmap-<d>/ic_launcher.png             legacy round icon (pre-adaptive launchers)
  mipmap-<d>/ic_launcher_foreground.png  adaptive foreground, 108dp canvas
  mipmap-<d>/ic_launcher_monochrome.png  themed-icon layer (Android 13+), alpha only
and mipmap-anydpi-v26/ic_launcher.xml (adaptive icon with background colour).

Usage: python3 make_icons.py <res-dir> [preview.png]
"""
import os
import sys

from PIL import Image, ImageDraw

NAVY = (31, 54, 89, 255)      # background
CREAM = (246, 239, 224, 255)  # gate and book
GOLD = (242, 181, 68, 255)    # star
DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}
SS = 8  # supersampling factor


def bezier(p0, p1, p2, n=24):
    return [((1 - t) ** 2 * p0[0] + 2 * (1 - t) * t * p1[0] + t * t * p2[0],
             (1 - t) ** 2 * p0[1] + 2 * (1 - t) * t * p1[1] + t * t * p2[1])
            for t in (i / n for i in range(n + 1))]


def star(cx, cy, ro, ri):
    import math
    pts = []
    for i in range(10):
        r = ro if i % 2 == 0 else ri
        a = -math.pi / 2 + i * math.pi / 5
        pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    return pts


def glyph(size_px, gate=CREAM, book=CREAM, star_col=GOLD, dx=0.0, dy=-1.0, k=0.78):
    """Draw the glyph on a transparent square of size_px, in 108-unit design coordinates."""
    big = size_px * SS
    img = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    u = big / 108.0

    def P(x, y):
        return ((54 + (x - 54) * k + dx) * u, (54 + (y - 54) * k + dy) * u)

    def poly(points, fill):
        d.polygon([P(x, y) for x, y in points], fill=fill)

    # Gate: arch (outer r=22, stroke 6) on two pillars, standing on a threshold.
    cx, cy, ro, sw = 54, 47, 22, 6.5
    x0, y0 = P(cx - ro, cy - ro)
    x1, y1 = P(cx + ro, cy + ro)
    d.arc((x0, y0, x1, y1), 180, 360, fill=gate, width=round(sw * u * k))
    poly([(cx - ro, cy - 0.3), (cx - ro + sw, cy - 0.3), (cx - ro + sw, 80), (cx - ro, 80)], gate)
    poly([(cx + ro - sw, cy - 0.3), (cx + ro, cy - 0.3), (cx + ro, 80), (cx + ro - sw, 80)], gate)
    tx0, ty0 = P(29.5, 80.5)
    tx1, ty1 = P(78.5, 85.5)
    d.rounded_rectangle((tx0, ty0, tx1, ty1), radius=1.6 * u * k, fill=gate)

    # Open book at the threshold, spine in the middle.
    gap = 1.1
    left_top = bezier((40.5, 61.5), (47, 59.5), (54 - gap, 64.5))
    left_bot = bezier((54 - gap, 77.5), (47, 73.5), (40.5, 75))
    poly(left_top + left_bot, book)
    right_top = bezier((54 + gap, 64.5), (61, 59.5), (67.5, 61.5))
    right_bot = bezier((67.5, 75), (61, 73.5), (54 + gap, 77.5))
    poly(right_top + right_bot, book)

    # Star in the arch.
    poly(star(54, 47.5, 6.4, 2.7), star_col)

    return img.resize((size_px, size_px), Image.LANCZOS)


def legacy(size_px):
    big = size_px * SS
    img = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    m = big * 0.02
    d.ellipse((m, m, big - m, big - m), fill=NAVY)
    img = img.resize((size_px, size_px), Image.LANCZOS)
    # Legacy icons have no 108dp bleed: scale the glyph so it fills the disc nicely.
    g = glyph(size_px, k=0.97, dy=-1.2)
    img.alpha_composite(g)
    return img


def main(res, preview=None):
    for name, scale in DENSITIES.items():
        out = os.path.join(res, f"mipmap-{name}")
        os.makedirs(out, exist_ok=True)
        legacy(int(48 * scale)).save(os.path.join(out, "ic_launcher.png"))
        n = int(108 * scale)
        glyph(n).save(os.path.join(out, "ic_launcher_foreground.png"))
        white = (255, 255, 255, 255)
        glyph(n, gate=white, book=white, star_col=white).save(os.path.join(out, "ic_launcher_monochrome.png"))

    os.makedirs(os.path.join(res, "values"), exist_ok=True)
    with open(os.path.join(res, "values", "colors.xml"), "w") as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
                '    <color name="ic_launcher_background">#%02X%02X%02X</color>\n'
                '</resources>\n' % NAVY[:3])
    any_dir = os.path.join(res, "mipmap-anydpi-v26")
    os.makedirs(any_dir, exist_ok=True)
    with open(os.path.join(any_dir, "ic_launcher.xml"), "w") as f:
        f.write(
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
            '    <background android:drawable="@color/ic_launcher_background" />\n'
            '    <foreground android:drawable="@mipmap/ic_launcher_foreground" />\n'
            '    <monochrome android:drawable="@mipmap/ic_launcher_monochrome" />\n'
            "</adaptive-icon>\n"
        )

    if preview:
        make_preview(preview)


def masked(size, shape, bg, fg):
    big = size * SS
    mask = Image.new("L", (big, big), 0)
    d = ImageDraw.Draw(mask)
    if shape == "circle":
        d.ellipse((0, 0, big - 1, big - 1), fill=255)
    else:
        d.rounded_rectangle((0, 0, big - 1, big - 1), radius=big * 0.30, fill=255)
    mask = mask.resize((size, size), Image.LANCZOS)
    # Adaptive icons show the middle 72/108 of the layers.
    full = int(size * 108 / 72)
    layer = Image.new("RGBA", (full, full), bg)
    layer.alpha_composite(fg(full))
    o = (full - size) // 2
    layer = layer.crop((o, o, o + size, o + size))
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(layer, (0, 0), mask)
    return out


def make_preview(path):
    """Large: circle, squircle, themed (Android 13+), grayscale (e-ink). Bottom row: real sizes."""
    sheet = Image.new("RGBA", (1150, 420), (236, 236, 236, 255))
    themed = (205, 222, 255, 255)
    tiles = [
        masked(240, "circle", NAVY, lambda n: glyph(n)),
        masked(240, "squircle", NAVY, lambda n: glyph(n)),
        masked(240, "circle", (40, 44, 52, 255), lambda n: glyph(n, gate=themed, book=themed, star_col=themed)),
        masked(240, "circle", NAVY, lambda n: glyph(n)).convert("LA").convert("RGBA"),
    ]
    for i, t in enumerate(tiles):
        sheet.alpha_composite(t, (30 + i * 280, 20))
    x = 30
    for s in (144, 96, 72, 48):
        sheet.alpha_composite(masked(s, "circle", NAVY, lambda n: glyph(n)), (x, 410 - s))
        x += s + 30
    sheet.alpha_composite(legacy(96), (x + 40, 410 - 96))
    sheet.save(path)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else None)
