#!/usr/bin/env python3
"""
Builds the structure file the Ponder scene plays inside.

Create authors these in-game with a schematic tool and checks the .nbt in. There is no such tool
here, so the layout is described in code and written straight out. That has one real advantage
worth keeping: the rig the player is shown and the rig the GameTests assert against are the same
arrangement, written down twice in the same repo rather than drawn once and hoped about.

Conventions copied from Create's own ponder files (assets/create/ponder/*.nbt): y=0 is a
checkerboard base plate of white concrete and snow, the build sits at y>=1, and the whole thing is
one block larger than the base plate the scene declares.

    python3 tools/generate_ponder.py
"""

import gzip
import os
import struct
import sys

DATA_VERSION = 3955  # 1.21.1


# --- a very small NBT writer ------------------------------------------------------------------


def _str(value):
    raw = value.encode('utf8')
    return struct.pack('>H', len(raw)) + raw


def _compound(pairs):
    out = b''
    for name, (tag, payload) in pairs:
        out += bytes([tag]) + _str(name) + payload
    return out + b'\x00'


def _list(tag, items):
    return bytes([tag]) + struct.pack('>i', len(items)) + b''.join(items)


def _int_list(values):
    return _list(3, [struct.pack('>i', v) for v in values])


def _int_array(values):
    return struct.pack('>i', len(values)) + b''.join(struct.pack('>i', v) for v in values)


def write_structure(path, size, palette, blocks):
    """
    palette: list of (name, {property: value}).
    blocks: list of (state_index, (x, y, z)) or (state_index, (x, y, z), block_entity_pairs).
    """
    palette_entries = []
    for name, properties in palette:
        pairs = [('Name', (8, _str(name)))]
        if properties:
            pairs.append(('Properties', (10, _compound(
                [(k, (8, _str(v))) for k, v in sorted(properties.items())]))))
        palette_entries.append(_compound(pairs))

    block_entries = []
    for entry in blocks:
        state, pos = entry[0], entry[1]
        pairs = [
            ('state', (3, struct.pack('>i', state))),
            ('pos', (9, _int_list(list(pos)))),
        ]
        if len(entry) > 2 and entry[2]:
            pairs.append(('nbt', (10, _compound(entry[2]))))
        block_entries.append(_compound(pairs))

    root = _compound([
        ('DataVersion', (3, struct.pack('>i', DATA_VERSION))),
        ('size', (9, _int_list(list(size)))),
        ('palette', (9, _list(10, palette_entries))),
        ('blocks', (9, _list(10, block_entries))),
        ('entities', (9, _list(0, []))),
    ])
    payload = b'\x0a' + _str('') + root

    os.makedirs(os.path.dirname(path), exist_ok=True)
    with gzip.GzipFile(path, 'wb', mtime=0) as handle:
        handle.write(payload)


# --- the scene ---------------------------------------------------------------------------------

PLATE = 5  # matches scene.configureBasePlate(0, 0, PLATE)


def _block_pos(name, pos):
    """A BlockPos as 1.21 writes one: an int array, which is what NbtUtils.readBlockPos wants."""
    return (name, (11, _int_array(list(pos))))


def air_engine_scene():
    """
    A creative motor on a shaft, an Air Engine, and a 2x2x2 vessel behind it.

    Laid out along +X at z=1..2 so the camera sees the engine's flywheel side. The scene pulls the
    motor out partway through to show the engine taking over, so the shaft between them is load
    bearing: without it the engine would have nothing left to drive.

    The vessel carries block entity data, and it has to. A ponder level never runs the connectivity
    handler -- forming a multiblock is server-side work and a ponder level is client-side -- so a
    vessel restored without a Controller pointer is eight separate one-block tanks that will not
    merge and whose textures will not connect. Create's own ponder structures are captured out of a
    real world where the multiblock had already formed, which is why theirs carry the same fields.
    Ponder re-anchors Controller by the offset between LastKnownPos and where the block lands, so
    the two only have to agree with each other, not with any real world.
    """
    palette = [
        ('minecraft:white_concrete', None),
        ('minecraft:snow_block', None),
        ('create:creative_motor', {'facing': 'east'}),
        ('create:shaft', {'axis': 'x', 'waterlogged': 'false'}),
        ('createcaes:air_engine', {'facing': 'east'}),
        # The ends of the tank and its middle are different blockstates, as with a Fluid Tank.
        ('createcaes:pressure_vessel', {'top': 'false', 'bottom': 'true'}),
        ('createcaes:pressure_vessel', {'top': 'true', 'bottom': 'false'}),
    ]
    blocks = []

    for x in range(PLATE):
        for z in range(PLATE):
            blocks.append(((x + z) % 2, (x, 0, z)))

    blocks.append((2, (0, 1, 1)))
    blocks.append((3, (1, 1, 1)))
    blocks.append((4, (2, 1, 1)))

    controller = (3, 1, 1)
    width, height = 2, 2
    for x in (3, 4):
        for z in (1, 2):
            for y in (1, 2):
                pos = (x, y, z)
                state = 5 if y == 1 else 6
                nbt = [('id', (8, _str('createcaes:pressure_vessel')))]
                if pos == controller:
                    nbt.append(('Size', (3, struct.pack('>i', width))))
                    nbt.append(('Height', (3, struct.pack('>i', height))))
                else:
                    nbt.append(_block_pos('Controller', controller))
                nbt.append(_block_pos('LastKnownPos', pos))
                blocks.append((state, pos, nbt))

    return (PLATE + 1, 4, PLATE + 1), palette, blocks


SCENES = {'air_engine': air_engine_scene}

# Java sources the scene text is read back out of, so the lang file cannot drift from the scene.
SCENE_SOURCES = {'air_engine': 'src/main/java/com/createcaes/client/ponder/AirEngineScenes.java'}

LANG = 'src/main/resources/assets/createcaes/lang/en_us.json'


def sync_scene_lang():
    """
    Writes the ponder lang keys from the strings in the scene source.

    Ponder resolves every line of scene text through I18n against a key it derives itself --
    `<namespace>.ponder.<sceneId>.header` and `.text_N`, numbered from one in call order. The English
    passed to `.text(...)` is only the datagen default; if the key is missing the player is shown the
    key. Create generates these in datagen. There is no datagen here, so they are generated from the
    one place they already exist: the scene.
    """
    import json
    import re

    with open(LANG) as handle:
        lang = json.load(handle)

    for scene_id, source_path in SCENE_SOURCES.items():
        source = open(source_path).read()
        title = re.search(r'scene\.title\("([^"]+)",\s*"([^"]+)"\)', source)
        if not title:
            raise SystemExit('%s has no scene.title(...) call' % source_path)
        if title.group(1) != scene_id:
            raise SystemExit('%s titles itself %r, expected %r'
                             % (source_path, title.group(1), scene_id))

        texts = re.findall(r'\n\t+\.text\("((?:[^"\\]|\\.)*)"\)', source)
        # A .text( the pattern above failed to match would silently lose a line at runtime and
        # nowhere else, so count them a second way and insist the two agree.
        expected = len(re.findall(r'\.text\(', source))
        if len(texts) != expected:
            raise SystemExit('%s: matched %d of %d .text( calls -- the pattern needs updating'
                             % (source_path, len(texts), expected))

        prefix = 'createcaes.ponder.%s.' % scene_id
        for key in [k for k in lang if k.startswith(prefix)]:
            del lang[key]
        lang[prefix + 'header'] = title.group(2)
        for index, text in enumerate(texts, start=1):
            lang[prefix + 'text_%d' % index] = text
        print('synced %d lang entries for %s' % (len(texts) + 1, scene_id))

    with open(LANG, 'w') as handle:
        json.dump(lang, handle, indent=2, ensure_ascii=False)
        handle.write('\n')


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else 'src/main/resources/assets/createcaes/ponder'
    for name, build in SCENES.items():
        size, palette, blocks = build()
        path = os.path.join(root, name + '.nbt')
        write_structure(path, size, palette, blocks)
        print('wrote %s (%s blocks)' % (path, len(blocks)))
    sync_scene_lang()


if __name__ == '__main__':
    main()
