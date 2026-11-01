"""
Cuts the 4K masters down for the APK and writes them as WebP into res/drawable-nodpi.

    python finish.py            every master
    python finish.py preview    the quick previews instead, to try a layout before the masters are done

A phone's screen is about 1080 wide, so shipping 4K would only cost the shop's phones memory: the 4K
goes into quality here, by the downsample averaging away the render's last noise.
Needs Pillow (pgAdmin's bundled python has it; there is no python on PATH on this machine).
"""
import os
import sys
from PIL import Image, ImageChops

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, "..", "app", "src", "main", "res", "drawable-nodpi")
SRC = os.path.join(HERE, "preview" if len(sys.argv) > 1 and sys.argv[1] == "preview" else "masters")


# Rendered, but no screen shows them yet: not carried in the APK.
UNUSED = {"ic3d_jewel", "ic3d_note", "ic3d_print", "ic3d_rupee", "ic3d_warning"}


def size_for(name):
    if name.startswith("plate_"):
        return (1440, 375), 84
    if name == "hero_login":
        return (1080, 1920), 82
    if name == "page_velvet":
        return (720, 1280), 62
    if name == "page_parchment":
        return (720, 1280), 90  # a pale ramp bands at any lower quality
    if name == "emblem":
        return (640, 640), 90
    return (288, 288), 88


def feather(w, h):
    """An icon's floor and shadow reach the frame; on a white card that edge showed as a grey box."""
    def ramp(d, span):
        t = min(max(d / span, 0.0), 1.0)
        return t * t * (3 - 2 * t)
    xs = [ramp(x, .08 * w) * ramp(w - 1 - x, .08 * w) for x in range(w)]
    ys = [ramp(y, .05 * h) * ramp(h - 1 - y, .16 * h) for y in range(h)]
    mask = Image.new("L", (w, h))
    mask.putdata([int(255 * a * b + .5) for b in ys for a in xs])
    return mask


os.makedirs(RES, exist_ok=True)
total = 0
for f in sorted(os.listdir(SRC)):
    if not f.endswith(".png") or f == "sheet.png":
        continue
    name = f[:-4]
    if name in UNUSED:
        continue
    (w, h), q = size_for(name)
    im = Image.open(os.path.join(SRC, f))
    if name.startswith(("ic3d_", "emblem")):
        # Resize with the colour held under the alpha, or the edges pick up a dark fringe.
        im = im.convert("RGBA").resize((w, h), Image.LANCZOS, reducing_gap=3.0)
        if name.startswith("ic3d_"):
            im.putalpha(ImageChops.multiply(im.getchannel("A"), feather(w, h)))
        out = os.path.join(RES, name + ".webp")
        im.save(out, "WEBP", quality=q, alpha_quality=92, method=6, exact=False)
    else:
        im = im.convert("RGB").resize((w, h), Image.LANCZOS, reducing_gap=3.0)
        out = os.path.join(RES, name + ".webp")
        im.save(out, "WEBP", quality=q, method=6)
    total += os.path.getsize(out)
print("%.2f MB into %s" % (total / 1048576.0, os.path.normpath(RES)))
