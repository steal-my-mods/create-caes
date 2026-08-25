#!/usr/bin/env python3
"""
Generates the mod badge: the Create-family circle of blue graph paper with this mod's subject
drawn large in front of it.

The badge *convention* -- a white-ringed azure disc of graph paper, the subject given a white
stroke and a soft shadow -- is what every Create addon uses to say "this plugs into Create", and a
convention is not artwork. Nothing here is copied from Create: the palette, the proportions and
the grid are the ones the sibling addons in this family already use, so the three sit together on
a mods list, and the subject is drawn from scratch.

The subject is a pressure vessel: a gas cylinder with a brass valve, two riveted copper hoops and
a gauge reading part-charged. A tank is the thing a player is actually building when they build
this mod, and a cylinder with a gauge on it is what compressed gas looks like wherever it is
drawn, which is why it reads without a caption.

It is drawn in pixels on a 16x20 grid and blown up by a whole number, so it is Minecraft-shaped
rather than a smooth vector illustration -- which is what the badge used to be, and what made it
the odd one out of the three. Everything else is described once at a 256px reference and scaled by
a whole factor, so `--size 512` is the same badge larger rather than a different one. Sizes must
be multiples of 256.

    python3 tools/generate_logo.py [output.png] [--size 256]
"""

import math
import os
import struct
import sys
import zlib

REFERENCE = 256                # the size every measurement below was tuned at
SS = 3                         # supersampling factor per axis

# --- badge palette, shared with the sibling addons so the three match ----------
WHITE       = (255.0, 255.0, 255.0)
FIELD_LIGHT = (104.0, 172.0, 217.0)
FIELD       = ( 75.0, 139.0, 193.0)
FIELD_DEEP  = ( 56.0, 114.0, 168.0)
GRID        = (126.0, 190.0, 228.0)
SHADOW      = ( 30.0,  64.0, 100.0)

# --- subject palette, lifted from tools/generate_textures.py -------------------
# The same steel, copper and brass the Pressure Vessel and the Air Engine are painted in, so the
# badge is the mod's own colours rather than a second set that only looks similar.
STEEL       = (110.0, 122.0, 133.0)
STEEL_LIGHT = (146.0, 158.0, 169.0)
STEEL_DARK  = ( 74.0,  83.0,  92.0)
STEEL_DEEP  = ( 52.0,  59.0,  66.0)
COPPER       = (196.0, 123.0,  78.0)
COPPER_LIGHT = (222.0, 156.0, 110.0)
COPPER_DARK  = (146.0,  88.0,  54.0)
COPPER_DEEP  = (110.0,  64.0,  38.0)
GUNMETAL_DARK = ( 38.0,  42.0,  48.0)   # the Air Engine's housing tone, borrowed for the bezel
BRASS       = (176.0, 141.0,  63.0)
BRASS_LIGHT = (214.0, 180.0,  96.0)
BRASS_DARK  = (128.0, 100.0,  40.0)
GAUGE_FACE  = (238.0, 240.0, 236.0)
GAUGE_SHADE = (206.0, 212.0, 214.0)
NEEDLE      = (190.0,  60.0,  50.0)

# --- weights, which are fractions rather than lengths and so do not scale ------
GRID_ALPHA = 0.28
SHADOW_ALPHA = 0.26

USAGE = ('usage: generate_logo.py [output.png] [--size N]  '
         '(N a positive multiple of {})'.format(REFERENCE))

# --- geometry, in reference pixels ---------------------------------------------
# One factor moves all of it, and it has to leave SPRITE_SCALE a whole number -- keeping the
# subject's pixels square is the entire reason it is scaled by an integer -- so the output size
# must be a multiple of REFERENCE. 256 is the in-jar logo; 512 is what CurseForge wants for a
# project icon, since it downscales gracefully and never upscales.
GEOMETRY = {
    'RADIUS': 124.0,           # outer edge of the badge
    'RING': 9.0,               # white ring thickness
    'GRID_SPACING': 46.0,      # the sibling badges' grid; the old 26 read as a fine mesh
    'GRID_HALF_WIDTH': 2.5,
    'SPRITE_SCALE': 9,         # whole number, so subject pixels stay square
    'STROKE': 6.0,             # white outline thickness
    'SHADOW_DX': 6.0,
    'SHADOW_DY': 8.0,
    'GLOW_DX': -44.0,          # where the light sits, relative to the centre
    'GLOW_DY': -52.0,
}


def configure(size):
    """Scales the geometry above to the requested output size. Call before rendering."""
    if size <= 0 or size % REFERENCE:
        raise SystemExit('size must be a positive multiple of {}, got {}'.format(REFERENCE, size))
    factor = size // REFERENCE
    globals().update({name: value * factor for name, value in GEOMETRY.items()})
    globals().update(OUT=size, N=size * SS, CX=size / 2.0, CY=size / 2.0)


def lerp(a, b, t):
    return (a[0] + (b[0] - a[0]) * t,
            a[1] + (b[1] - a[1]) * t,
            a[2] + (b[2] - a[2]) * t)


# --- the subject ---------------------------------------------------------------

SPRITE_W, SPRITE_H = 16, 20

BODY_LEFT, BODY_RIGHT = 2, 15   # half-open, so the body is columns 2..14, centred on x=8
HOOP_ROWS = (5, 16)             # the two copper hoops, hard against the dome and the foot
HOOP_HEIGHT = 2
RIVET_COLUMNS = (4, 8, 12)
GAUGE_LEFT, GAUGE_TOP = 5, 8    # a seven-by-seven dial, centred between the hoops
GAUGE_SIZE = 7

# How far in each row of the dial is cut, top to bottom. Two, one, none, none, none, one, two --
# an octagon, which is the roundest thing a seven-pixel square can be. Six wide was tried first
# and came out a white square with a diagonal on it: at this size a dial needs an odd width so it
# has a middle row, and a cut of two so the corners are gone rather than merely nibbled.
DIAL_CUTS = (2, 1, 0, 0, 0, 1, 2)


def blank_sprite():
    return [[None] * SPRITE_W for _ in range(SPRITE_H)]


def rect(cells, x0, y0, x1, y1, colour):
    for y in range(max(0, y0), min(SPRITE_H, y1)):
        for x in range(max(0, x0), min(SPRITE_W, x1)):
            cells[y][x] = colour


def put(cells, x, y, colour):
    if 0 <= x < SPRITE_W and 0 <= y < SPRITE_H:
        cells[y][x] = colour


def across(x, tones):
    """
    Picks a tone for one column of the tank, so a row painted column by column comes out round.

    Five bands rather than a gradient: the edge that curves away from the viewer, the lit face,
    the middle, the shaded face, and the edge in shadow. A gradient at nine screen pixels per
    sprite pixel is just a gradient with square corners, which is the thing this badge is trying
    not to be.
    """
    edge_light, light, mid, dark, edge_dark = tones
    if x <= BODY_LEFT:
        return edge_light
    if x <= 5:
        return light
    if x <= 11:
        return mid
    if x <= 13:
        return dark
    return edge_dark


STEEL_TONES = (STEEL_DARK, STEEL_LIGHT, STEEL, STEEL_DARK, STEEL_DEEP)
COPPER_TONES = (COPPER_DARK, COPPER_LIGHT, COPPER, COPPER_DARK, COPPER_DEEP)


def subject_sprite():
    """
    A pressure vessel, as (width, height, rows-of-RGBA).

    Read top to bottom it is: a squat brass valve, a two-step dome, the body with a riveted
    copper hoop at each end and a gauge between them, and a dark steel foot. Three of those
    choices are load-bearing, and each was arrived at by drawing the alternative:

      * The steps in the dome. An unbroken rectangle with hoops on it is a barrel, and a barrel
        is a storage crate to anyone who plays this game.
      * The proportion, half again as tall as it is wide. The first draft was thirteen rows of
        body on a fourteen-wide sprite with the same hoops and the same dial, and came out an
        aerosol can with a label on it -- the shape was doing all of the work.
      * The valve being wide and short. A three-wide cap on a collar is a spray nozzle; five wide
        over a seven-wide collar is a fitting, and it tapers into the dome instead of perching
        on it.

    The copper stops at the hoops, and that is a fourth thing that was drawn both ways. Steel on
    an azure field is low contrast -- the badge wants the warmth -- but taking the copper any
    further, onto the dome or the foot, turns the tank into a barrel with a lid on it. Two hoops
    two rows deep is as much as the shape survives.

    The silhouette is deliberately solid: the gauge is painted over the body rather than cut out
    of it. That is what lets the white stroke be a plain distance band, with no flood fill to tell
    a hole from the outside -- the sibling badge has one because a gantry has gaps between its
    legs. Cut an opening into this drawing and you will need theirs.
    """
    cells = blank_sprite()

    # Valve and collar. Brass, because that is what the Air Engine's ports are trimmed in, and it
    # is the one warm note above the gauge.
    rect(cells, 6, 0, 11, 1, BRASS_LIGHT)
    rect(cells, 6, 1, 11, 2, BRASS)
    rect(cells, 5, 2, 12, 3, BRASS_DARK)
    put(cells, 10, 0, BRASS)
    put(cells, 10, 1, BRASS_DARK)

    # The body, and the dome pressed into the top of it: two steps in, so the top reads as
    # spun rather than sawn off.
    for y in range(3, 18):
        inset = {3: 2, 4: 1}.get(y, 0)
        for x in range(BODY_LEFT + inset, BODY_RIGHT - inset):
            cells[y][x] = across(x, STEEL_TONES)
    for x in range(BODY_LEFT + 2, BODY_RIGHT - 5):
        cells[3][x] = STEEL_LIGHT

    # The foot: two rows, not three. Three was a black slab that dragged the whole badge down.
    # Dark steel rather than the Air Engine's gunmetal -- this is a vessel, and the vessel is
    # painted in steel and copper.
    for x in range(BODY_LEFT, BODY_RIGHT):
        cells[18][x] = STEEL_DARK if x <= 11 else STEEL_DEEP
    rect(cells, BODY_LEFT + 1, 19, BODY_RIGHT - 1, 20, STEEL_DEEP)

    # The hoops, riveted. Rivets are one sprite pixel each, which is nine on a 256px badge -- big
    # enough to read as hardware and small enough not to become the subject.
    for hoop in HOOP_ROWS:
        for y in range(hoop, hoop + HOOP_HEIGHT):
            for x in range(BODY_LEFT, BODY_RIGHT):
                cells[y][x] = across(x, COPPER_TONES)
        for x in RIVET_COLUMNS:
            cells[hoop + 1][x] = COPPER_DEEP

    gauge(cells)

    rows = [[(cell + (255,)) if cell else (0, 0, 0, 0) for cell in row] for row in cells]
    return SPRITE_W, SPRITE_H, [[tuple(int(round(v)) for v in px) for px in row] for row in rows]


def gauge(cells):
    """
    The dial: a gunmetal bezel, a pale face lit from the top left, and a needle at two o'clock.

    The bezel is gunmetal rather than dark steel so that it separates from the tank it is bolted
    to -- steel on steel at one pixel of rim is a smudge, and the ring is the only thing making
    the dial round. The corners the octagon cuts back from are filled with dark steel rather than
    left as plain tank: a near-black ring with its four corners showing body colour is a plus sign,
    not a circle, which is exactly what the first version of this looked like at 256px. Dark
    steel and not the deep tone, either -- fill the corners that dark and the bezel stops being a
    ring at all and becomes a black plate with a dial painted on it.

    The needle's pivot sits one cell down and left of the dial's centre so that the needle itself
    can be two cells of clean diagonal and still land on face rather than on the bezel. Every
    arrangement with a centred pivot either put the tip on the rim, where it reads as a stray dot
    outside the glass, or bent the needle into an L.

    Two thirds is the reading on purpose. A full gauge says the tank is done with; an empty one
    says the mod does not work. Two thirds is the state a player actually spends their time in,
    and the only one that says the thing is *working*.
    """
    for row, cut in enumerate(DIAL_CUTS):
        y = GAUGE_TOP + row
        for column in range(GAUGE_SIZE):
            x = GAUGE_LEFT + column
            if column < cut or column >= GAUGE_SIZE - cut:
                cells[y][x] = STEEL_DARK          # the corner the bezel is cut back from
                continue
            inner_row = 0 < row < GAUGE_SIZE - 1
            inner_column = cut < column < GAUGE_SIZE - cut - 1
            cells[y][x] = GAUGE_FACE if (inner_row and inner_column) else GUNMETAL_DARK

    # The glass, shaded away from the light, so the face is not a flat white blob.
    for x in range(GAUGE_LEFT + 2, GAUGE_LEFT + 5):
        cells[GAUGE_TOP + 5][x] = GAUGE_SHADE
    cells[GAUGE_TOP + 4][GAUGE_LEFT + 5] = GAUGE_SHADE

    put(cells, 7, 12, GUNMETAL_DARK)              # the pivot
    put(cells, 8, 11, NEEDLE)
    put(cells, 9, 10, NEEDLE)


def opaque_bounds(width, height, pixels):
    """Bounding box of the visible part, so the badge centres on the art not the canvas."""
    min_x, min_y, max_x, max_y = width, height, -1, -1
    for y in range(height):
        for x in range(width):
            if pixels[y][x][3] > 0:
                min_x = min(min_x, x)
                max_x = max(max_x, x)
                min_y = min(min_y, y)
                max_y = max(max_y, y)
    if max_x < 0:
        raise ValueError('subject is entirely transparent')
    return min_x, min_y, max_x + 1, max_y + 1


def check_fits(width, height, pixels, bounds):
    """
    Refuses a subject whose stroked silhouette would run off the edge of the disc.

    Growing the sprite by a row, or nudging SPRITE_SCALE up one, is the obvious way to make the
    badge bolder and the failure is not obvious at all: the corner nearest the rim gets shaved
    flat by the clip in render(), which at a glance reads as a design choice rather than as the
    drawing being too big. So measure the far corner of every opaque cell, add the stroke, and say
    so here instead. The margin is small on purpose -- the point of the check is to allow the
    subject right up to the ring.
    """
    min_x, min_y, max_x, max_y = bounds
    left = CX - (max_x - min_x) * SPRITE_SCALE / 2.0 - min_x * SPRITE_SCALE
    top = CY - (max_y - min_y) * SPRITE_SCALE / 2.0 - min_y * SPRITE_SCALE

    worst = 0.0
    for y in range(height):
        for x in range(width):
            if not pixels[y][x][3]:
                continue
            for cx in (left + x * SPRITE_SCALE, left + (x + 1) * SPRITE_SCALE):
                for cy in (top + y * SPRITE_SCALE, top + (y + 1) * SPRITE_SCALE):
                    worst = max(worst, math.hypot(cx - CX, cy - CY))

    limit = RADIUS - RING
    if worst + STROKE > limit:
        raise SystemExit(
            'subject overruns the disc: {:.1f}px of stroked art against a {:.1f}px field. '
            'Drop SPRITE_SCALE or trim the sprite.'.format(worst + STROKE, limit))
    return worst + STROKE, limit


def place_sprite():
    """Blows the subject up to badge scale, returning a supersampled colour buffer."""
    width, height, pixels = subject_sprite()
    bounds = opaque_bounds(width, height, pixels)
    check_fits(width, height, pixels, bounds)
    min_x, min_y, max_x, max_y = bounds

    drawn_width = (max_x - min_x) * SPRITE_SCALE
    drawn_height = (max_y - min_y) * SPRITE_SCALE
    left = CX - drawn_width / 2.0 - min_x * SPRITE_SCALE
    top = CY - drawn_height / 2.0 - min_y * SPRITE_SCALE

    step = SPRITE_SCALE * SS
    buffer = [None] * (N * N)
    for y in range(height):
        for x in range(width):
            r, g, b, a = pixels[y][x]
            if not a:
                continue
            packed = (r, g, b)
            x0 = int(round((left + x * SPRITE_SCALE) * SS))
            y0 = int(round((top + y * SPRITE_SCALE) * SS))
            for gy in range(max(0, y0), min(N, y0 + step)):
                row = gy * N
                for gx in range(max(0, x0), min(N, x0 + step)):
                    buffer[row + gx] = packed
    return buffer


def outline_distance(buffer, reach):
    """
    Chamfer distance from the subject, in supersampled pixels, so the white stroke can be taken as
    a band around it. Two sweeps, which is plenty for so short a reach.
    """
    far = float(reach + 2)
    distance = [0.0 if cell is not None else far for cell in buffer]
    straight, diagonal = 1.0, 1.41421356

    for y in range(N):
        row = y * N
        previous = row - N
        for x in range(N):
            index = row + x
            best = distance[index]
            if best == 0.0:
                continue
            if x > 0:
                best = min(best, distance[index - 1] + straight)
            if y > 0:
                best = min(best, distance[previous + x] + straight)
                if x > 0:
                    best = min(best, distance[previous + x - 1] + diagonal)
                if x < N - 1:
                    best = min(best, distance[previous + x + 1] + diagonal)
            distance[index] = best

    for y in range(N - 1, -1, -1):
        row = y * N
        following = row + N
        for x in range(N - 1, -1, -1):
            index = row + x
            best = distance[index]
            if best == 0.0:
                continue
            if x < N - 1:
                best = min(best, distance[index + 1] + straight)
            if y < N - 1:
                best = min(best, distance[following + x] + straight)
                if x < N - 1:
                    best = min(best, distance[following + x + 1] + diagonal)
                if x > 0:
                    best = min(best, distance[following + x - 1] + diagonal)
            distance[index] = best

    return distance


def background(x, y):
    """The graph-paper field at one point, before the subject is laid over it."""
    glow = math.hypot(x - (CX + GLOW_DX), y - (CY + GLOW_DY)) / (RADIUS * 1.55)
    colour = lerp(FIELD_LIGHT, FIELD, min(1.0, glow))
    distance = math.hypot(x - CX, y - CY)
    rim = min(1.0, max(0.0, (distance / RADIUS - 0.55) / 0.45)) ** 1.4
    colour = lerp(colour, FIELD_DEEP, rim)
    for coordinate in (x, y):
        offset = abs(((coordinate + GRID_SPACING / 2.0) % GRID_SPACING) - GRID_SPACING / 2.0)
        if offset < GRID_HALF_WIDTH:
            colour = lerp(colour, GRID, GRID_ALPHA)
    return colour


def render():
    buffer = place_sprite()
    reach = STROKE * SS
    distance = outline_distance(buffer, reach)

    shadow_dx = int(round(SHADOW_DX * SS))
    shadow_dy = int(round(SHADOW_DY * SS))
    inner = RADIUS - RING

    rows = []
    samples = SS * SS
    for py in range(OUT):
        row = []
        for px in range(OUT):
            r = g = b = a = 0.0
            for sy in range(SS):
                gy = py * SS + sy
                y = (gy + 0.5) / SS
                for sx in range(SS):
                    gx = px * SS + sx
                    x = (gx + 0.5) / SS

                    from_centre = math.hypot(x - CX, y - CY)
                    if from_centre > RADIUS:
                        continue
                    if from_centre > inner:
                        colour = WHITE
                    else:
                        index = gy * N + gx
                        cell = buffer[index]
                        if cell is not None:
                            colour = (float(cell[0]), float(cell[1]), float(cell[2]))
                        elif distance[index] <= reach:
                            colour = WHITE
                        else:
                            colour = background(x, y)
                            sx0, sy0 = gx - shadow_dx, gy - shadow_dy
                            if 0 <= sx0 < N and 0 <= sy0 < N:
                                cast = sy0 * N + sx0
                                if buffer[cast] is not None or distance[cast] <= reach:
                                    colour = lerp(colour, SHADOW, SHADOW_ALPHA)

                    r += colour[0]
                    g += colour[1]
                    b += colour[2]
                    a += 1.0

            if a <= 0.0:
                row.append((0, 0, 0, 0))
                continue
            row.append((
                int(round(min(255.0, r / a))),
                int(round(min(255.0, g / a))),
                int(round(min(255.0, b / a))),
                int(round(255.0 * a / samples)),
            ))
        rows.append(row)
    return rows


def write_png(path, rows):
    height, width = len(rows), len(rows[0])
    raw = b''.join(b'\x00' + b''.join(struct.pack('BBBB', *p) for p in row) for row in rows)

    def chunk(kind, data):
        return (struct.pack('>I', len(data)) + kind + data
                + struct.pack('>I', zlib.crc32(kind + data) & 0xffffffff))

    png = (b'\x89PNG\r\n\x1a\n'
           + chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 6, 0, 0, 0))
           + chunk(b'IDAT', zlib.compress(raw, 9))
           + chunk(b'IEND', b''))
    directory = os.path.dirname(path)
    if directory:
        os.makedirs(directory, exist_ok=True)
    with open(path, 'wb') as handle:
        handle.write(png)
    return len(png)


def main():
    arguments = sys.argv[1:]
    size = REFERENCE
    if '--size' in arguments:
        at = arguments.index('--size')
        if at + 1 >= len(arguments):
            raise SystemExit('--size needs a value: %s' % USAGE)
        try:
            size = int(arguments[at + 1])
        except ValueError:
            raise SystemExit('--size wants a whole number, got %r: %s'
                             % (arguments[at + 1], USAGE))
        del arguments[at:at + 2]
    target = arguments[0] if arguments else 'src/main/resources/createcaes_icon.png'

    configure(size)
    written = write_png(target, render())
    print('wrote {} ({}x{}, {} bytes)'.format(target, OUT, OUT, written))


if __name__ == '__main__':
    main()
