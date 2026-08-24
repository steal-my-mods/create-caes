#!/usr/bin/env python3
"""
Generates the mod badge: the Create-family circle of blue graph paper with this mod's subject
in front of it.

Nothing here is copied from Create. The badge *convention* -- a white-ringed azure disc with the
mod's headline object on it -- is what every Create addon uses to say "this plugs into Create",
and a convention is not artwork. The palette is chosen here, the geometry is described here, and
the subject is drawn from scratch: a pressure vessel, banded and gauged, because a tank is the
thing a player is actually building when they build this mod.

The drawing is described once at a 256px reference and scaled, so `--size 512` is the same badge
larger rather than a different one. Sizes must be multiples of 256 so the maths stays exact.

    python3 tools/generate_logo.py [output.png] [--size 256]
"""

import math
import os
import struct
import sys
import zlib

REFERENCE = 256
SS = 3  # supersampling per axis

WHITE = (255.0, 255.0, 255.0)
FIELD_LIGHT = (104.0, 172.0, 217.0)
FIELD = (75.0, 139.0, 193.0)
FIELD_DEEP = (56.0, 114.0, 168.0)
GRID = (126.0, 190.0, 228.0)
SHADOW = (30.0, 64.0, 100.0)

STEEL_LIGHT = (168.0, 180.0, 191.0)
STEEL = (118.0, 130.0, 142.0)
STEEL_DARK = (74.0, 83.0, 92.0)
COPPER = (196.0, 123.0, 78.0)
COPPER_DARK = (146.0, 88.0, 54.0)
GAUGE_FACE = (238.0, 240.0, 236.0)
NEEDLE = (190.0, 60.0, 50.0)

GRID_ALPHA = 0.28
SHADOW_ALPHA = 0.26

# Reference-pixel geometry. One factor scales all of it.
DISC_RADIUS = 124.0
RING_WIDTH = 9.0
GRID_SPACING = 26.0
GRID_WIDTH = 2.0

TANK_HALF_W = 44.0
TANK_TOP = 52.0
TANK_BOTTOM = 204.0
TANK_ROUND = 26.0
BAND_YS = (88.0, 168.0)
BAND_H = 13.0
GAUGE_CENTRE = (128.0, 128.0)
GAUGE_RADIUS = 25.0
OUTLINE = 5.0
SHADOW_OFFSET = 7.0


def mix(a, b, t):
    return tuple(a[i] + (b[i] - a[i]) * t for i in range(3))


def rounded_box_distance(x, y, cx, cy, half_w, half_h, radius):
    """Signed distance to a rounded rectangle: negative inside."""
    dx = abs(x - cx) - (half_w - radius)
    dy = abs(y - cy) - (half_h - radius)
    outside = math.hypot(max(dx, 0.0), max(dy, 0.0))
    inside = min(max(dx, dy), 0.0)
    return outside + inside - radius


def sample(x, y):
    """Colour and alpha at one supersample, in reference coordinates."""
    cx = cy = REFERENCE / 2.0
    r = math.hypot(x - cx, y - cy)

    if r > DISC_RADIUS:
        colour, alpha = WHITE, 0.0
    elif r > DISC_RADIUS - RING_WIDTH:
        colour, alpha = WHITE, 1.0
    else:
        # Azure field, lighter towards the top left the way a lit sphere is.
        lean = ((x - cx) * -0.5 + (y - cy) * -0.5) / DISC_RADIUS
        colour = mix(FIELD, FIELD_LIGHT, max(0.0, min(1.0, 0.45 + lean)))
        colour = mix(colour, FIELD_DEEP, max(0.0, (r / DISC_RADIUS) ** 3 * 0.55))
        on_grid = (x % GRID_SPACING < GRID_WIDTH) or (y % GRID_SPACING < GRID_WIDTH)
        if on_grid:
            colour = mix(colour, GRID, GRID_ALPHA)
        alpha = 1.0

    tank_half_h = (TANK_BOTTOM - TANK_TOP) / 2.0
    tank_cy = (TANK_BOTTOM + TANK_TOP) / 2.0

    # Shadow first, so the subject sits on the field rather than floating over it.
    if alpha > 0.0:
        d_shadow = rounded_box_distance(x - SHADOW_OFFSET, y - SHADOW_OFFSET, REFERENCE / 2.0,
                                        tank_cy, TANK_HALF_W + OUTLINE, tank_half_h + OUTLINE,
                                        TANK_ROUND)
        if d_shadow < 0.0:
            colour = mix(colour, SHADOW, SHADOW_ALPHA)

    d = rounded_box_distance(x, y, REFERENCE / 2.0, tank_cy, TANK_HALF_W, tank_half_h, TANK_ROUND)

    if d < OUTLINE and alpha == 0.0:
        alpha = 1.0  # the outline may spill past the disc; keep it opaque where it does

    if d < 0.0:
        # Body: a vertical cylinder, so shading runs across it rather than down it.
        across = (x - REFERENCE / 2.0) / TANK_HALF_W
        colour = mix(STEEL, STEEL_LIGHT, max(0.0, 1.0 - abs(across + 0.35) * 1.6))
        colour = mix(colour, STEEL_DARK, max(0.0, (across - 0.1) * 0.9))

        for band_y in BAND_YS:
            if band_y <= y < band_y + BAND_H:
                edge = min(y - band_y, band_y + BAND_H - y) / BAND_H
                colour = mix(COPPER_DARK, COPPER, min(1.0, edge * 3.0))
                colour = mix(colour, STEEL_DARK, max(0.0, (across - 0.2) * 0.7))

        gr = math.hypot(x - GAUGE_CENTRE[0], y - GAUGE_CENTRE[1])
        if gr < GAUGE_RADIUS:
            if gr > GAUGE_RADIUS - 4.0:
                colour = STEEL_DARK
            else:
                colour = GAUGE_FACE
                # Needle at about two o'clock: charged, not full.
                angle = math.atan2(y - GAUGE_CENTRE[1], x - GAUGE_CENTRE[0])
                target = math.radians(-52.0)
                if gr > 3.0 and abs(math.atan2(math.sin(angle - target),
                                               math.cos(angle - target))) < 0.16:
                    colour = NEEDLE
                if gr <= 4.0:
                    colour = STEEL_DARK
    elif d < OUTLINE:
        colour = WHITE

    return colour, alpha


def render(size):
    scale = size / REFERENCE
    pixels = bytearray()
    for py in range(size):
        for px in range(size):
            r = g = b = a = 0.0
            for sy in range(SS):
                for sx in range(SS):
                    x = (px + (sx + 0.5) / SS) / scale
                    y = (py + (sy + 0.5) / SS) / scale
                    colour, alpha = sample(x, y)
                    r += colour[0] * alpha
                    g += colour[1] * alpha
                    b += colour[2] * alpha
                    a += alpha
            n = SS * SS
            if a > 0.0:
                pixels.extend((int(r / a + 0.5), int(g / a + 0.5), int(b / a + 0.5),
                               int(a / n * 255 + 0.5)))
            else:
                pixels.extend((0, 0, 0, 0))
    return pixels


def write_png(path, size, rgba):
    raw = bytearray()
    row = size * 4
    for y in range(size):
        raw.append(0)
        raw.extend(rgba[y * row:(y + 1) * row])

    def chunk(tag, data):
        body = tag + data
        return struct.pack('>I', len(data)) + body + struct.pack('>I', zlib.crc32(body))

    png = b'\x89PNG\r\n\x1a\n'
    png += chunk(b'IHDR', struct.pack('>IIBBBBB', size, size, 8, 6, 0, 0, 0))
    png += chunk(b'IDAT', zlib.compress(bytes(raw), 9))
    png += chunk(b'IEND', b'')
    os.makedirs(os.path.dirname(path) or '.', exist_ok=True)
    with open(path, 'wb') as handle:
        handle.write(png)


def main():
    args = [a for a in sys.argv[1:]]
    size = REFERENCE
    if '--size' in args:
        i = args.index('--size')
        size = int(args[i + 1])
        del args[i:i + 2]
    if size % REFERENCE != 0:
        raise SystemExit('--size must be a multiple of %d' % REFERENCE)
    out = args[0] if args else 'src/main/resources/createcaes_icon.png'
    write_png(out, size, render(size))
    print('wrote %s at %dx%d' % (out, size, size))


if __name__ == '__main__':
    main()
