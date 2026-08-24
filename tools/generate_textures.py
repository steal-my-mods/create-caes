#!/usr/bin/env python3
"""
Draws every texture this mod ships.

None of Create's art is used or derived from: Create's code is MIT but everything under its
assets/ is All Rights Reserved, so the only safe amount of it to copy is none. What is borrowed
is the *convention* -- 16x16, a flat base with two shade steps, hard 1px highlights, rivets at
the corners -- which is how Minecraft block art has looked since 2011 and is not anyone's to own.

Everything is deterministic: the "noise" is a hash of the coordinate, so re-running this produces
byte-identical files and a diff in the repo means someone changed the drawing.

    python3 tools/generate_textures.py [output_root]

Default output root is src/main/resources/assets/createcaes/textures.
"""

import os
import struct
import sys
import zlib

# --- PNG ------------------------------------------------------------------------------------


def write_png(path, width, height, pixels):
    """pixels: flat list of (r, g, b, a) tuples, row-major from the top left."""
    raw = bytearray()
    for y in range(height):
        raw.append(0)  # filter type 0 (None) -- these are tiny, compression is not the point
        for x in range(width):
            raw.extend(pixels[y * width + x])

    def chunk(tag, data):
        body = tag + data
        return struct.pack('>I', len(data)) + body + struct.pack('>I', zlib.crc32(body))

    png = b'\x89PNG\r\n\x1a\n'
    png += chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 6, 0, 0, 0))
    png += chunk(b'IDAT', zlib.compress(bytes(raw), 9))
    png += chunk(b'IEND', b'')

    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'wb') as handle:
        handle.write(png)


# --- helpers --------------------------------------------------------------------------------


def noise(x, y, salt):
    """A stable -1/0/1 per pixel, so a texture looks worked rather than printed."""
    h = (x * 374761393 + y * 668265263 + salt * 2246822519) & 0xFFFFFFFF
    h = (h ^ (h >> 13)) * 1274126177 & 0xFFFFFFFF
    return ((h >> 7) % 3) - 1


def shade(colour, amount):
    return tuple(max(0, min(255, c + amount)) for c in colour[:3]) + (colour[3],)


def canvas(width, height, colour):
    return [colour] * (width * height)


def put(pixels, width, x, y, colour):
    pixels[y * width + x] = colour


def rect(pixels, width, x0, y0, x1, y1, colour):
    for y in range(y0, y1):
        for x in range(x0, x1):
            put(pixels, width, x, y, colour)


def hline(pixels, width, y, colour, x0=0, x1=16):
    rect(pixels, width, x0, y, x1, y + 1, colour)


def grain(pixels, width, height, salt, strength=6):
    for y in range(height):
        for x in range(width):
            pixels[y * width + x] = shade(pixels[y * width + x], noise(x, y, salt) * strength)


# --- palette --------------------------------------------------------------------------------

STEEL = (110, 122, 133, 255)
STEEL_DARK = (74, 83, 92, 255)
STEEL_LIGHT = (146, 158, 169, 255)
STEEL_DEEP = (52, 59, 66, 255)

COPPER = (196, 123, 78, 255)
COPPER_DARK = (146, 88, 54, 255)
COPPER_LIGHT = (222, 156, 110, 255)

GUNMETAL = (58, 63, 70, 255)
GUNMETAL_DARK = (38, 42, 48, 255)
GUNMETAL_LIGHT = (82, 89, 98, 255)

BRASS = (176, 141, 63, 255)
BRASS_DARK = (128, 100, 40, 255)
BRASS_LIGHT = (214, 180, 96, 255)

AIR = (198, 226, 240, 255)
AIR_DARK = (156, 194, 216, 255)
AIR_LIGHT = (231, 245, 252, 255)


def rivets(pixels, width, ys, xs, colour, highlight):
    for y in ys:
        for x in xs:
            put(pixels, width, x, y, colour)
            put(pixels, width, x, y - 1, highlight)


# --- pressure vessel ------------------------------------------------------------------------
#
# The vessel uses Create's connected-texture system, so a 3x3 tower reads as one tank rather than
# nine stacked crates. That means the art is not one tile but a sheet of 64: every combination of
# "which of my edges continue into the same tank" needs its own tile, and which tile a face gets is
# decided at render time by Create's AllCTTypes.OMNIDIRECTIONAL.
#
# Rather than hand-place 64 tiles and hope they line up with Create's indexing, ct_index below is a
# port of that indexing, and the sheet is built by asking it. Every one of the 256 possible
# neighbourhoods is enumerated, mapped to a tile, and the tile is drawn from what those
# neighbourhoods agree it should look like. Get the port wrong and the sheet is wrong in exactly the
# same way the renderer is, which is the only kind of wrong that stays invisible.


def ct_index(up, down, left, right, top_left, top_right, bottom_left, bottom_right):
    """Port of AllCTTypes.OMNIDIRECTIONAL.getTextureIndex. Returns 0..63 into an 8x8 sheet."""
    tile_x = 0
    tile_y = 0
    borders = (0 if up else 1) + (0 if down else 1) + (0 if left else 1) + (0 if right else 1)

    if up:
        tile_x += 1
    if down:
        tile_x += 2
    if left:
        tile_y += 1
    if right:
        tile_y += 2

    if borders == 0:
        if top_right:
            tile_x += 1
        if top_left:
            tile_x += 2
        if bottom_right:
            tile_y += 2
        if bottom_left:
            tile_y += 1

    if borders == 1:
        if not right and (top_left or bottom_left):
            tile_y = 4
            tile_x = -1 + (1 if bottom_left else 0) + (2 if top_left else 0)
        if not left and (top_right or bottom_right):
            tile_y = 5
            tile_x = -1 + (1 if bottom_right else 0) + (2 if top_right else 0)
        if not down and (top_left or top_right):
            tile_y = 6
            tile_x = -1 + (1 if top_left else 0) + (2 if top_right else 0)
        if not up and (bottom_left or bottom_right):
            tile_y = 7
            tile_x = -1 + (1 if bottom_left else 0) + (2 if bottom_right else 0)

    if borders == 2 and (
        (up and left and top_left)
        or (down and left and bottom_left)
        or (up and right and top_right)
        or (down and right and bottom_right)
    ):
        tile_x += 3

    return tile_x + 8 * tile_y


def ct_tile_specs():
    """
    For each of the 64 tiles, which edges carry a border and which corners carry an inner notch.

    A tile is reached by many neighbourhoods. They always agree about the edges -- that is what the
    index is built from -- so the edges are read off the first one. A corner is only notched when
    every neighbourhood mapping to the tile agrees it is an inner corner, which keeps ambiguous
    tiles plain rather than speckled.
    """
    specs = {}
    for bits in range(256):
        flags = [(bits >> i) & 1 == 1 for i in range(8)]
        up, down, left, right, tl, tr, bl, br = flags
        index = ct_index(up, down, left, right, tl, tr, bl, br)
        if not 0 <= index < 64:
            raise AssertionError('index %d out of range for %r' % (index, flags))

        edges = (up, down, left, right)
        corners = (
            up and left and not tl,
            up and right and not tr,
            down and left and not bl,
            down and right and not br,
        )
        if index not in specs:
            specs[index] = [edges, list(corners)]
        else:
            if specs[index][0] != edges:
                raise AssertionError('tile %d disagrees about its edges' % index)
            specs[index][1] = [a and b for a, b in zip(specs[index][1], corners)]
    return specs


def wall_plate(salt):
    """The uninterrupted middle of a tank wall: brushed plate with a faint vertical weld."""
    px = canvas(16, 16, STEEL)
    grain(px, 16, 16, salt, 5)
    rect(px, 16, 7, 0, 8, 16, shade(STEEL, -12))
    rect(px, 16, 8, 0, 9, 16, shade(STEEL, 8))
    return px


def cap_plate(salt):
    """The end of a tank: the same plate cross-braced, so a lid does not read as more wall."""
    px = canvas(16, 16, shade(STEEL, -6))
    grain(px, 16, 16, salt, 4)
    rect(px, 16, 0, 7, 16, 9, shade(STEEL, -16))
    rect(px, 16, 7, 0, 9, 16, shade(STEEL, -16))
    rect(px, 16, 7, 7, 9, 9, STEEL_DEEP)
    return px


def draw_ct_tile(edges, corners, interior, salt, accent, accent_dark):
    """One tile of the sheet: plate, plus a riveted copper-trimmed border on every open edge."""
    up, down, left, right = edges
    px = interior(salt)

    def border_row(y, inner):
        for x in range(16):
            put(px, 16, x, y, accent_dark if not inner else accent)

    def border_col(x, inner):
        for y in range(16):
            put(px, 16, x, y, accent_dark if not inner else accent)

    if not up:
        border_row(0, False)
        border_row(1, True)
    if not down:
        border_row(15, False)
        border_row(14, True)
    if not left:
        border_col(0, False)
        border_col(1, True)
    if not right:
        border_col(15, False)
        border_col(14, True)

    # Rivets sit just inside an open edge, so the frame of a whole tank is studded and its
    # interior is clean.
    if not up:
        for x in (3, 12):
            put(px, 16, x, 3, STEEL_DEEP)
            put(px, 16, x, 2, STEEL_LIGHT)
    if not down:
        for x in (3, 12):
            put(px, 16, x, 12, STEEL_DEEP)
            put(px, 16, x, 13, STEEL_LIGHT)
    if not left:
        for y in (3, 12):
            put(px, 16, 3, y, STEEL_DEEP)
    if not right:
        for y in (3, 12):
            put(px, 16, 12, y, STEEL_DEEP)

    # Inner corners: where two edges continue but the diagonal does not, the frame turns a corner.
    notch_tl, notch_tr, notch_bl, notch_br = corners
    if notch_tl:
        rect(px, 16, 0, 0, 2, 2, accent_dark)
    if notch_tr:
        rect(px, 16, 14, 0, 16, 2, accent_dark)
    if notch_bl:
        rect(px, 16, 0, 14, 2, 16, accent_dark)
    if notch_br:
        rect(px, 16, 14, 14, 16, 16, accent_dark)
    return px


def vessel_ct_sheet(interior, salt, accent, accent_dark):
    """The 8x8 sheet Create indexes into, as one 128x128 image."""
    specs = ct_tile_specs()
    sheet = canvas(128, 128, STEEL)
    for index in range(64):
        edges, corners = specs.get(index, [(False, False, False, False), [False] * 4])
        tile = draw_ct_tile(edges, corners, interior, salt, accent, accent_dark)
        ox = (index % 8) * 16
        oy = (index // 8) * 16
        for y in range(16):
            for x in range(16):
                sheet[(oy + y) * 128 + ox + x] = tile[y * 16 + x]
    return sheet


def vessel_side():
    """The lone-block face, and the sprite the CT sheet shifts away from: bordered on all four."""
    return draw_ct_tile((False, False, False, False), [False] * 4, wall_plate, 3, COPPER, COPPER_DARK)


def vessel_cap():
    return draw_ct_tile((False, False, False, False), [False] * 4, cap_plate, 7, STEEL_LIGHT,
                        STEEL_DEEP)


# --- air engine -----------------------------------------------------------------------------


def engine_side():
    """Gunmetal housing with a brass band where the flywheel runs."""
    px = canvas(16, 16, GUNMETAL)
    grain(px, 16, 16, 11, 5)

    hline(px, 16, 0, GUNMETAL_LIGHT)
    hline(px, 16, 15, GUNMETAL_DARK)

    # The band sits at the height the flywheel is modelled at, so the two read as one machine.
    for y, colour in ((10, BRASS_DARK), (11, BRASS), (12, BRASS_LIGHT), (13, BRASS_DARK)):
        hline(px, 16, y, colour)

    rect(px, 16, 2, 3, 14, 8, GUNMETAL_DARK)
    rect(px, 16, 3, 4, 13, 7, shade(GUNMETAL, -4))
    rivets(px, 16, (2,), (1, 14), (24, 27, 31, 255), GUNMETAL_LIGHT)
    return px


def engine_port():
    """The face bolted to the vessel: a flange with a bolt circle."""
    px = canvas(16, 16, GUNMETAL_DARK)
    grain(px, 16, 16, 13, 4)

    rect(px, 16, 1, 1, 15, 15, GUNMETAL)
    rect(px, 16, 4, 4, 12, 12, BRASS_DARK)
    rect(px, 16, 5, 5, 11, 11, BRASS)
    rect(px, 16, 6, 6, 10, 10, GUNMETAL_DARK)
    rect(px, 16, 7, 7, 9, 9, (18, 20, 24, 255))

    for x, y in ((2, 2), (13, 2), (2, 13), (13, 13), (7, 1), (7, 14), (1, 7), (14, 7)):
        put(px, 16, x, y, (24, 27, 31, 255))
    return px


def engine_back():
    """The shaft side: a bearing housing with a square socket for the shaft."""
    px = canvas(16, 16, GUNMETAL)
    grain(px, 16, 16, 17, 4)

    rect(px, 16, 2, 2, 14, 14, GUNMETAL_DARK)
    rect(px, 16, 3, 3, 13, 13, GUNMETAL)
    rect(px, 16, 5, 5, 11, 11, BRASS_DARK)
    rect(px, 16, 6, 6, 10, 10, (16, 18, 21, 255))
    rect(px, 16, 7, 7, 9, 9, (10, 11, 13, 255))

    for x, y in ((3, 3), (12, 3), (3, 12), (12, 12)):
        put(px, 16, x, y, (24, 27, 31, 255))
    return px


def engine_flywheel():
    """Brass wheel face with spokes, so the spin is legible when it is turning."""
    px = canvas(16, 16, BRASS)
    grain(px, 16, 16, 19, 6)

    hline(px, 16, 0, BRASS_LIGHT)
    hline(px, 16, 15, BRASS_DARK)
    rect(px, 16, 0, 0, 1, 16, BRASS_LIGHT)
    rect(px, 16, 15, 0, 16, 16, BRASS_DARK)

    rect(px, 16, 7, 2, 9, 14, BRASS_DARK)
    rect(px, 16, 2, 7, 14, 9, BRASS_DARK)
    rect(px, 16, 6, 6, 10, 10, BRASS_LIGHT)
    rect(px, 16, 7, 7, 9, 9, (58, 45, 18, 255))
    return px


# --- compressed air -------------------------------------------------------------------------


def air_still():
    px = canvas(16, 16, AIR)
    for y in range(16):
        for x in range(16):
            n = noise(x, y, 23)
            colour = AIR_LIGHT if n > 0 else (AIR_DARK if n < 0 else AIR)
            px[y * 16 + x] = colour
    # A few brighter motes so a tank of it does not read as a flat panel.
    for x, y in ((3, 4), (11, 2), (6, 9), (13, 12), (1, 13), (8, 6)):
        put(px, 16, x, y, (255, 255, 255, 255))
    return px


def air_flow():
    px = canvas(16, 16, AIR)
    for y in range(16):
        for x in range(16):
            band = (y + (x // 4)) % 4
            colour = (AIR_LIGHT, AIR, AIR_DARK, AIR)[band]
            px[y * 16 + x] = shade(colour, noise(x, y, 29) * 4)
    return px


# --- entry point ----------------------------------------------------------------------------


TEXTURES = {
    'block/pressure_vessel_side': vessel_side,
    'block/pressure_vessel_cap': vessel_cap,
    'block/air_engine_side': engine_side,
    'block/air_engine_port': engine_port,
    'block/air_engine_back': engine_back,
    'block/air_engine_flywheel': engine_flywheel,
    'fluid/compressed_air_still': air_still,
    'fluid/compressed_air_flow': air_flow,
}


SHEETS = {
    'block/pressure_vessel_side_ct': lambda: vessel_ct_sheet(wall_plate, 3, COPPER, COPPER_DARK),
    'block/pressure_vessel_cap_ct': lambda: vessel_ct_sheet(cap_plate, 7, STEEL_LIGHT, STEEL_DEEP),
}


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else 'src/main/resources/assets/createcaes/textures'
    for name, draw in TEXTURES.items():
        path = os.path.join(root, name + '.png')
        write_png(path, 16, 16, draw())
        print('wrote', path)
    for name, draw in SHEETS.items():
        path = os.path.join(root, name + '.png')
        write_png(path, 128, 128, draw())
        print('wrote', path, '(connected-texture sheet)')


if __name__ == '__main__':
    main()
