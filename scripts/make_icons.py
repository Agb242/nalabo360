"""Generate Nalabo360 launcher icons from the brand wordmark.

Extracts the compass "O" glyph (rightmost glyph of the wordmark), recolours it
white with alpha derived from its distance to the white background, and composes:
  - Android adaptive-icon foreground: 432x432 transparent PNG, glyph in the
    safe zone (composeApp/src/androidMain/res/drawable-nodpi/)
  - iOS app icons: full-bleed 120x120 and 180x180 on the brand navy
    (iosApp/)
"""

from PIL import Image

SRC = r"C:\Users\User\Downloads\ChatGPT Image 26 août 2026, 03_02_19.png"
ANDROID_OUT = r"C:\Users\User\ZCodeProject\360-photo-app\composeApp\src\androidMain\res\drawable-nodpi\ic_launcher_foreground.png"
IOS_OUT_120 = r"C:\Users\User\ZCodeProject\360-photo-app\iosApp\AppIcon60x60@2x.png"
IOS_OUT_180 = r"C:\Users\User\ZCodeProject\360-photo-app\iosApp\AppIcon60x60@3x.png"

BRAND_NAVY = (13, 37, 45)  # #0D252D, sampled from the wordmark


def extract_glyph():
    im = Image.open(SRC).convert("RGB")
    w, h = im.size
    px = im.load()

    def is_ink(x, y):
        return sum(px[x, y]) < 700

    # The "6" kerns into the "O", so no white column separates them. Flood-fill
    # the ink component that touches the right edge — the O's ring — then keep
    # every ink pixel inside the ring's bounding box, which picks up the needle
    # floating in its middle.
    start = None
    for x in range(w - 1, -1, -1):
        row_hit = next((y for y in range(h) if is_ink(x, y)), None)
        if row_hit is not None:
            start = (x, row_hit)
            break
    assert start is not None, "no ink anywhere"

    from collections import deque

    seen = set()
    queue = deque([start])
    while queue:
        x, y = queue.popleft()
        if (x, y) in seen or not (0 <= x < w and 0 <= y < h) or not is_ink(x, y):
            continue
        seen.add((x, y))
        queue.extend(((x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)))

    xs = [p[0] for p in seen]
    ys = [p[1] for p in seen]
    left, right, top, bottom = min(xs), max(xs), min(ys), max(ys)
    print(f"ring bbox: x {left}..{right}, y {top}..{bottom} "
          f"({right - left + 1}x{bottom - top + 1})")

    # Union the ring with everything inked inside its box (the needle).
    keep = set(p for p in seen if left <= p[0] <= right and top <= p[1] <= bottom)
    for y in range(top, bottom + 1):
        for x in range(left, right + 1):
            if (x, y) not in keep and is_ink(x, y):
                keep.add((x, y))

    crop_w, crop_h = right - left + 1, bottom - top + 1
    out = Image.new("RGBA", (crop_w, crop_h), (255, 255, 255, 0))
    op = out.load()
    for (x, y) in keep:
        r, g, b = px[x, y]
        alpha = max(255 - r, 255 - g, 255 - b)
        op[x - left, y - top] = (255, 255, 255, min(255, alpha))
    return out


def compose(glyph, canvas_px, glyph_fraction, background=None):
    canvas = Image.new("RGBA", (canvas_px, canvas_px), background or (0, 0, 0, 0))
    target = int(canvas_px * glyph_fraction)
    scale = target / max(glyph.size)
    resized = glyph.resize((max(1, int(glyph.size[0] * scale)), max(1, int(glyph.size[1] * scale))), Image.LANCZOS)
    pos = ((canvas_px - resized.size[0]) // 2, (canvas_px - resized.size[1]) // 2)
    canvas.alpha_composite(resized, pos)
    return canvas


glyph = extract_glyph()

# Android adaptive foreground: 108dp canvas at xxxhdpi, glyph inside the
# 66dp safe zone (61% of the canvas) with margin to spare.
compose(glyph, 432, 0.54).convert("RGBA").save(ANDROID_OUT)
print("wrote", ANDROID_OUT)

# iOS full-bleed square icons; iOS applies its own mask.
for size, path in ((120, IOS_OUT_120), (180, IOS_OUT_180)):
    icon = Image.new("RGBA", (size, size), BRAND_NAVY + (255,))
    g = compose(glyph, size, 0.60)
    icon.alpha_composite(g)
    icon.convert("RGB").save(path)
    print("wrote", path)
