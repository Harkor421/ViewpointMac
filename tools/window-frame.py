#!/usr/bin/env python3
"""Puts a screenshot in a macOS window: a dark title bar with the three window buttons and a
title, rounded corners, a hairline edge and a soft shadow, on a transparent background, so it
sits well on GitHub's light and dark themes. Needs Pillow (with WebP for .webp output).

    tools/window-frame.py in.jpg out.webp [title]
"""
import sys

from PIL import Image, ImageDraw, ImageFilter, ImageFont

TITLE_BAR = 56            # px, for a 1600 px wide picture (28 pt at 2x)
RADIUS = 26               # window corners
BUTTON = 24               # traffic light diameter
BUTTON_GAP = 16
BUTTON_LEFT = 24          # from the window's edge to the first button
MARGIN = 72               # room for the shadow
SHADOW_BLUR = 28
SHADOW_DROP = 18
BUTTONS = [((255, 95, 87), (224, 68, 62)), ((254, 188, 46), (222, 161, 35)), ((40, 200, 64), (26, 171, 41))]
BAR = (40, 40, 43, 255)
TITLE_COLOUR = (214, 214, 219, 255)
FONTS = ["/System/Library/Fonts/SFNS.ttf", "/System/Library/Fonts/Helvetica.ttc"]


def font(size):
    for path in FONTS:
        try:
            return ImageFont.truetype(path, size)
        except OSError:
            pass
    return ImageFont.load_default()


def frame(shot, title):
    w, h = shot.size
    win_w, win_h = w, h + TITLE_BAR
    window = Image.new("RGBA", (win_w, win_h), BAR)
    window.paste(shot.convert("RGBA"), (0, TITLE_BAR))
    d = ImageDraw.Draw(window)
    # The bar's lower edge.
    d.line([(0, TITLE_BAR - 1), (win_w, TITLE_BAR - 1)], fill=(0, 0, 0, 160), width=1)
    y = TITLE_BAR // 2
    for k, (fill, rim) in enumerate(BUTTONS):
        x = BUTTON_LEFT + BUTTON // 2 + k * (BUTTON + BUTTON_GAP)
        d.ellipse([x - BUTTON // 2, y - BUTTON // 2, x + BUTTON // 2, y + BUTTON // 2], fill=fill, outline=rim, width=1)
    f = font(25)
    tw = d.textlength(title, font=f)
    d.text(((win_w - tw) / 2, y), title, font=f, fill=TITLE_COLOUR, anchor="lm")

    # Rounded corners, with a hairline edge that reads on both dark and light pages.
    mask = Image.new("L", (win_w, win_h), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, win_w - 1, win_h - 1], RADIUS, fill=255)
    window.putalpha(mask)
    ImageDraw.Draw(window).rounded_rectangle([0, 0, win_w - 1, win_h - 1], RADIUS, outline=(255, 255, 255, 46), width=2)

    canvas = Image.new("RGBA", (win_w + 2 * MARGIN, win_h + 2 * MARGIN), (0, 0, 0, 0))
    shadow = Image.new("L", canvas.size, 0)
    ImageDraw.Draw(shadow).rounded_rectangle(
        [MARGIN, MARGIN + SHADOW_DROP, MARGIN + win_w, MARGIN + win_h + SHADOW_DROP], RADIUS, fill=120)
    shadow = shadow.filter(ImageFilter.GaussianBlur(SHADOW_BLUR))
    canvas.putalpha(shadow)
    canvas.alpha_composite(window, (MARGIN, MARGIN))
    return canvas


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    out = frame(Image.open(sys.argv[1]), sys.argv[3] if len(sys.argv) > 3 else "Project Zomboid")
    if sys.argv[2].lower().endswith(".webp"):
        out.save(sys.argv[2], "WEBP", quality=88, method=6)
    else:
        out.save(sys.argv[2])


if __name__ == "__main__":
    main()
