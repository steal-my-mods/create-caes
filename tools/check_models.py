#!/usr/bin/env python3
"""
Checks the Air Engine's models for the two ways its geometry can be wrong on screen while
logging a perfectly clean startup: a rotating part that grinds through the housing it turns in,
and coplanar faces that z-fight.

Finds the one rendering fault that overlapping block-model boxes actually produce: two quads on
the same plane, facing the same way, that do not draw the same pixels. Those z-fight. Boxes that
merely interpenetrate are fine -- the depth buffer sorts them out, and a rod entering a housing is
supposed to look like that -- so interpenetration is reported as information, not as a fault.

Two quads draw the same pixels only if they share a texture *and* a uv. The Air Engine's web and
its spokes both sample block/air_engine_flywheel, but at different uvs, so their coincident faces
are as visible a fight as two different textures would be.

Element rotation is always about Y here, so up and down normals survive it and every pair must be
checked on the y planes. A rotated box's x/z normals only line up with another box turned by the
same angle, so those are checked within a rotation group, in that group's own frame.

    python3 tools/check_model_overlaps.py

Exits non-zero on a fault, so it can gate a build.
"""

import itertools
import json
import math
import os
import sys

ROOT = 'src/main/resources/assets/createcaes/models/block'

# The engine's block model plus the two partials the renderer draws over it, and the piston's
# travel: the rod slides, so its extremes are separate arrangements that each have to be clean.
PARTS = ('air_engine', 'air_engine_flywheel', 'air_engine_piston')
PISTON_THROW = 2.0
FACES = (('down', 1, -1), ('up', 1, +1), ('west', 0, -1), ('east', 0, +1),
         ('north', 2, -1), ('south', 2, +1))


def load(name):
    with open(os.path.join(ROOT, name + '.json')) as handle:
        return json.load(handle)


def rotation_of(element):
    rot = element.get('rotation')
    if not rot:
        return 0.0
    assert rot['axis'] == 'y', 'only Y rotation is handled; see the docstring'
    return float(rot['angle'])


def boxes(offset):
    """Every box, with its faces resolved to the (texture, uv) pair that decides its pixels."""
    out = []
    for name in PARTS:
        model = load(name)
        dy = offset if name == 'air_engine_piston' else 0.0
        for i, element in enumerate(model['elements']):
            lo = [float(v) for v in element['from']]
            hi = [float(v) for v in element['to']]
            lo[1] += dy
            hi[1] += dy
            pixels = {}
            for face, axis, sign in FACES:
                spec = element['faces'].get(face)
                if spec:
                    texture = spec['texture']
                    resolved = model['textures'].get(texture[1:], texture)
                    pixels[(axis, sign)] = (resolved, tuple(spec['uv']))
            out.append(dict(name=f'{name.replace("air_engine", "engine")}[{i}]',
                            lo=lo, hi=hi, angle=rotation_of(element), pixels=pixels))
    return out


def in_frame(box, angle):
    """The box's extent in the frame of `angle`, which is exact when the box shares that angle."""
    if box['angle'] == angle:
        return box['lo'], box['hi']
    rad = math.radians(-angle)
    cos, sin = math.cos(rad), math.sin(rad)
    own = math.radians(box['angle'])
    ocos, osin = math.cos(own), math.sin(own)
    xs, zs = [], []
    for x in (box['lo'][0], box['hi'][0]):
        for z in (box['lo'][2], box['hi'][2]):
            # into world space by the box's own rotation, then into the target frame
            wx, wz = 8 + (x - 8) * ocos - (z - 8) * osin, 8 + (x - 8) * osin + (z - 8) * ocos
            xs.append(8 + (wx - 8) * cos - (wz - 8) * sin)
            zs.append(8 + (wx - 8) * sin + (wz - 8) * cos)
    return ([min(xs), box['lo'][1], min(zs)], [max(xs), box['hi'][1], max(zs)])


def spread(a_lo, a_hi, b_lo, b_hi, skip):
    """Overlap area of two axis-aligned boxes on the two axes that are not `skip`."""
    out = 1.0
    for axis in range(3):
        if axis == skip:
            continue
        span = min(a_hi[axis], b_hi[axis]) - max(a_lo[axis], b_lo[axis])
        if span <= 1e-9:
            return 0.0
        out *= span
    return out


def check(offset):
    parts = boxes(offset)
    faults, info = [], []
    for a, b in itertools.combinations(parts, 2):
        for axis in range(3):
            # y normals survive a Y rotation, so they compare across every pair; x and z only
            # line up between boxes turned by the same angle.
            if axis != 1 and a['angle'] != b['angle']:
                continue
            frame = a['angle']
            a_lo, a_hi = in_frame(a, frame)
            b_lo, b_hi = in_frame(b, frame)
            for sign in (-1, +1):
                pa, pb = a['pixels'].get((axis, sign)), b['pixels'].get((axis, sign))
                if not pa or not pb:
                    continue
                ca = a_hi[axis] if sign > 0 else a_lo[axis]
                cb = b_hi[axis] if sign > 0 else b_lo[axis]
                if abs(ca - cb) > 1e-9:
                    continue
                area = spread(a_lo, a_hi, b_lo, b_hi, axis)
                if area <= 1e-9:
                    continue
                if pa != pb:
                    faults.append(f"  Z-FIGHT  {a['name']} x {b['name']}  "
                                  f"{'xyz'[axis]}{'+' if sign > 0 else '-'} faces both at "
                                  f"{ca:g}, overlapping {area:.2f}\n"
                                  f"           {pa[0]} uv{list(pa[1])}\n"
                                  f"        vs {pb[0]} uv{list(pb[1])}")
        a_lo, a_hi = in_frame(a, a['angle'])
        b_lo, b_hi = in_frame(b, a['angle'])
        vol = 1.0
        for axis in range(3):
            vol *= max(0.0, min(a_hi[axis], b_hi[axis]) - max(a_lo[axis], b_lo[axis]))
        if vol > 1e-9:
            info.append(f"  interpenetrates ({vol:.1f})  {a['name']} x {b['name']}")
    return faults, info



# --- the rotating part must fit where it turns ----------------------------------------------
#
# Two rules, both of them Create's. A partial that turns needs a silhouette that barely changes
# as it turns -- millstone/inner.json is four bars at 0/45/90/135, flat radius 9.00 and sweep
# radius 9.12 -- and it needs open air to turn in, which for the millstone is the band its
# housing simply has no geometry in. 0.1.0 broke both and the flywheel ground through its own
# housing; these assertions are what stop that coming back.

WALL = 6.0    # the housing's side wall, as a distance from the rotation axis
EDGE = 8.0    # the block boundary


def sweep(element):
    """Greatest distance any corner reaches from the axis, and the axis-aligned reach over a
    full revolution. The boundary the part must stay inside is a square, not a circle."""
    lo, hi = element['from'], element['to']
    own = math.radians(rotation_of(element))
    pts = []
    for x in (lo[0], hi[0]):
        for z in (lo[2], hi[2]):
            dx, dz = x - 8, z - 8
            pts.append((dx * math.cos(own) - dz * math.sin(own),
                        dx * math.sin(own) + dz * math.cos(own)))
    radius = max(math.hypot(x, z) for x, z in pts)
    reach = max(max(abs(x * math.cos(a) - z * math.sin(a)),
                    abs(x * math.sin(a) + z * math.cos(a)))
                for x, z in pts
                for a in (math.radians(d / 4) for d in range(4 * 360)))
    return radius, reach, (float(lo[1]), float(hi[1]))


def check_sweep():
    bands = [(float(e['from'][1]), float(e['to'][1])) for e in load('air_engine')['elements']]
    faults = []
    print('--- flywheel sweep (housing occupies y ' +
          ', '.join(f'{a:g}..{b:g}' for a, b in bands) + ')')
    for i, element in enumerate(load('air_engine_flywheel')['elements']):
        radius, reach, (y0, y1) = sweep(element)
        blocked = [b for b in bands if y0 < b[1] and b[0] < y1]
        # a part that never leaves the housing footprint is buried in it, not grinding through it
        through = bool(blocked) and radius > WALL
        outside = reach > EDGE + 1e-9
        note = ('GRINDS THROUGH HOUSING' if through else
                'leaves the block' if outside else
                'buried in housing' if blocked else 'turns in the open band')
        if through or outside:
            faults.append(i)
        print(f'  flywheel[{i}]  y {y0:5.2f}..{y1:5.2f}  sweep r {radius:5.2f}  '
              f'reach {reach:5.2f}  {note}')
    print(f'  {len(faults)} fault(s)\n')
    return len(faults)


def main():
    total = check_sweep()
    for offset in (-PISTON_THROW, 0.0, PISTON_THROW):
        faults, info = check(offset)
        total += len(faults)
        print(f'--- piston stroke {offset:+g}')
        for line in faults:
            print(line)
        print(f'  {len(faults)} fault(s), {len(info)} benign interpenetration(s)\n')
    if total:
        print(f'{total} fault(s) -- coincident faces that will z-fight')
        return 1
    print('no faults: the flywheel turns clear of the housing, and no two same-facing\ncoplanar quads draw different pixels')
    return 0


if __name__ == '__main__':
    sys.exit(main())
