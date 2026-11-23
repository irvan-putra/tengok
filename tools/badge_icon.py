#!/usr/bin/env python3
"""tengok launcher icon: an eye, because tengok means "take a look".

Same shared rules as the other vanipy apps: numbers-only version strip across
the bottom 24%, picture inside the round-mask safe circle, deterministic
output (pinned Pillow + pinned DejaVu font vendored in tools/fonts/, fail
instead of substituting).
    pip install pillow==12.3.0
"""
import math, os, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

PILLOW_PIN = "12.3.0"
import PIL
from PIL import ImageDraw

if PIL.__version__ != PILLOW_PIN:
    sys.exit("Pillow %s required, found %s" % (PILLOW_PIN, PIL.__version__))

# reuse the shared, tested drawing helpers from the sibling convention:
# circle mask checks, density writer, version parsing
import importlib.util

HELPERS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "badge_common.py")
spec = importlib.util.spec_from_file_location("badge_common", HELPERS)
common = importlib.util.module_from_spec(spec)
spec.loader.exec_module(common)

S = common.S


def eye(version):
    BG = (31, 42, 92, 255)        # tengok ink
    STRIP = (19, 26, 58, 255)     # darker ink
    WHITE = (250, 250, 247, 255)
    AMBER = (255, 179, 0, 255)
    INK = (31, 42, 92, 255)
    art, d = common.new_layer()
    # almond-shaped eye outline centered above the strip: two arcs
    cx, cy = 0.5, 0.44
    w, h = 0.62, 0.30
    pts_top, pts_bottom = [], []
    for i in range(81):
        t = i / 80
        x = cx - w / 2 + w * t
        arc = math.sin(math.pi * t)
        pts_top.append((x * S, (cy - h / 2 * arc) * S))
        pts_bottom.append((x * S, (cy + h / 2 * arc) * S))
    d.polygon(pts_top + pts_bottom[::-1], fill=WHITE)
    # iris and pupil
    d.ellipse([(cx - 0.115) * S, (cy - 0.115) * S, (cx + 0.115) * S, (cy + 0.115) * S], fill=AMBER)
    d.ellipse([(cx - 0.058) * S, (cy - 0.058) * S, (cx + 0.058) * S, (cy + 0.058) * S], fill=INK)
    # glint
    d.ellipse([(cx - 0.032) * S, (cy - 0.075) * S, (cx + 0.005) * S, (cy - 0.038) * S], fill=WHITE)
    return common.compose(BG, art, version, STRIP, WHITE, rounded=.2)


def main():
    version = common.parse_version(sys.argv)
    root = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
    # single-variant convention (2026-11-23): one full-color drawable at
    # xxxhdpi-class 192px, no density ladder
    res = os.path.join(root, "app/src/main/res")
    out = os.path.join(res, "drawable")
    os.makedirs(out, exist_ok=True)
    eye(version).resize((192, 192), common.Image.LANCZOS).save(
        os.path.join(out, "ic_launcher.png"), optimize=True)
    print("wrote tengok icon for", version)


if __name__ == "__main__":
    main()
