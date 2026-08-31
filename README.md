# Create: CAES

**Compressed Air Energy Storage for [Create](https://github.com/Creators-of-Create/Create).**
Minecraft 1.21.1 · NeoForge 21.1.219+ · Create 6.0+

A kinetic network in Create has no memory. A water wheel that turns all night while the factory
sleeps is turning for nothing, and the moment every machine starts at once the whole network trips.
This mod gives it a battery — a mechanical one, with no wires and no electricity anywhere in it.

An **Air Engine** bolted to a **Pressure Vessel** watches the network it is attached to. While there
is stress capacity going spare it runs as a compressor, drawing that surplus and pumping Compressed
Air into the vessel. When the rest of the network can no longer carry its own load, the same block
acts as a motor instead, spending stored air to supply the capacity that is missing. It switches
on its own; there is nothing to wire up and nothing to toggle.

## Building one

```
[ shaft ] — [ Air Engine ] — [ Pressure Vessel ]
```

The Air Engine's shaft goes on the face opposite the one pointing at the vessel — place it against a
vessel and it orients itself. Stack Pressure Vessels up to 3x3 wide and 24 tall; they merge into one
tank the way Create's Fluid Tanks do, and capacity scales with the number of blocks. Point goggles at
either block to see what is happening; a comparator on a vessel reads its fill level.

Compressed Air is a real fluid, so the vessel does not have to be bolted to the engine. Run Create
pipes from a pump to a vessel farm somewhere else and the engine will draw from whatever fluid
handler is on the face it points at.

## How it decides

Every tick the engine works out the network's balance **excluding its own contribution**, and:

| It sees | It does |
|---|---|
| The rest of the network cannot carry its own load | **Generate** — spend air, supply stress |
| Spare capacity for its draw, plus a margin | **Compress** — draw stress, store air |
| Anything in between | **Idle** |

Excluding itself is the whole trick. An engine that measured the total would compress, see the
deficit its own draw created, flip to generating, see the surplus its own capacity created, and flip
straight back — once a tick, for ever. Measuring what everything *else* is doing means the number it
tests does not move when it acts on it, and the margin leaves a band in the middle where neither test
fires.

The margin does one more job: it is what stops two engines back to back on a shaft from charging each
other. A compressor needs strictly more spare capacity than it will draw, and a motor of the same
rating supplies exactly as much as a compressor wants — so the loop never closes.

A generating engine only pays for the stress it is actually being asked for, down to a small floor so
that a shaft spinning on stored air is never free.

## Speed tiers

Generation is rated the way a Steam Engine's is. The vessel decides an efficiency, and the
efficiency picks one of four tiers:

| Tier | Ceiling | Worth |
|---|---|---|
| 1 | 16 RPM | `efficiency x maxStress` |
| 2 | 32 RPM | …the same |
| 3 | 48 RPM | …the same |
| 4 | 64 RPM | …the same |

**The tier is a ceiling, not a target.** An engine taking over a network that was running at 8 RPM
keeps it at 8 RPM — it does not yank everything up to 64 and change every belt speed and machine
timing in the factory. The tier only decides how fast the engine can drive a network that has no
speed of its own, which in practice means one that has stopped entirely.

That costs nothing. Whether the engine can carry the load is decided by its per-RPM rating, and a
consumer's draw scales with speed in exactly the same way, so the coverage at 8 RPM and at 64 RPM is
identical. Nor can it be geared around: the cap is a minimum against the tier, so a faster network
raises what the engine inherits but never what it is worth.

The total is flat across the tiers because capacity is divided by the tier as the ceiling rises — so
gearing your network up does **not** multiply what the engine is worth, exactly as it does not for
any other generator in Create. What a bigger vessel buys is a higher ceiling, more engines, and
longer to run.

An engine bolted to a vessel reads that vessel's size. An engine fed through a pipe has no size to
read and runs at a flat 0.125 efficiency — the same allowance Create gives an unheated boiler.

**Direction never changes.** The engine watches which way the shaft turns while it is being driven
and remembers it, so switching to a motor keeps the rotation exactly as it was.

## Balance

One number ties rotation to air: `airPerStressUnit`, millibuckets per Stress Unit per tick. Charging
multiplies it by `roundTripEfficiency`; discharging does not.

`roundTripEfficiency` is **1.0 by default** — the round trip is lossless. An engine and its vessel are
a buffer, and sizing generation to average load instead of peak is the reason to build one; Create
charges nothing for a water wheel, a belt or a gearbox; and this mod already makes you pay for
building small through the vessel-size efficiency below. Lower it if your pack wants storage to cost
something. It is not what stops an engine compressing against another store — the
`c:kinetic_energy_storage` tag does that, at any setting.

Efficiency is `min(1, min(18, vesselBlocks / 9) / attachedEngines)` — the same shape as Create's
boiler, which is `min(18, boilerSize / 4)` shared between the engines on it. Two figures fall out:

**A vessel supplies about 910su per block**, up to eighteen engines' worth. Past nine blocks per
engine efficiency is already 1.0, so every further block is duration rather than power.

**Runtime in seconds is `10,000 x blocks / load`,** where load is the stress actually being asked of
the engine. A motor pays only for what is drawn from it, down to a floor of 10% of its rating, so an
idling network drains a vessel ten times slower than a saturated one.

| Vessel | Blocks | Ceiling | Holds | Covers 4,000su for |
|---|---|---|---|---|
| 1x1x8 | 8 | 7,300su | 64,000mB | 20s |
| 2x2x12 | 48 | 43,700su | 384,000mB | 2min |
| 3x3x32 | 288 | 147,000su | 2,304,000mB | 12min |

For a moderately sized factory, **one engine and one big vessel** is the shape: a single engine at
full efficiency is worth 8,192su, and the vessel decides how long that lasts.

**Charging is the constraint, not storage.** A compressor draws its per-RPM rating times the network
speed, so a tier-4 engine on a 32 RPM shaft wants 4,096su of *spare* capacity before it will start.
If that is more surplus than you have, put the compressor on a geared-down branch — at 8 RPM the
same engine draws a quarter as much and fills four times slower. Point goggles at an idle engine and
it says what it needs and what is going spare.

## Vessel sizes

Footprints go up to 3x3, and any of them may be up to 32 tall — one cap for every width, as Create's
Fluid Tank does it. Stacked vessels merge into one tank and their textures connect, so a tower reads
as a single tank rather than a pile of crates. Two vessels built side by side stay separate:
separate air, separate outline.

Once a vessel is wider than one block, placing against its top or bottom face lays a whole course at
once, as with a Fluid Tank or an Item Vault. Sneak to place single blocks.

## Recipes

| | |
|---|---|
| **Pressure Vessel** ×2 | a ring of 4 copper sheets and 4 iron ingots |
| **Air Engine** | a brass sheet over a copper casing between two copper sheets, on a shaft |

## Known gaps in 0.1.0

- The vessel is opaque; there is no window and no fluid rendered inside it. Fill level reads out on a
  comparator and under goggles instead.
- No JEI/EMI integration beyond the stress figures Create shows on the item.
- No advancements, and nothing in the world marks a mode change — no particles, no sound, no
  indicator lamp. The mode reads under goggles only.
- With Create's `disableStress` set, the Air Engine does nothing at all. There is no surplus and no
  deficit to read on such a server, so it has no job — but the block is inert rather than saying so.

## Links

- **Source and issues:** <https://github.com/steal-my-mods/create-caes>
- **Requires:** [Create](https://github.com/Creators-of-Create/Create) 6.0+ for Minecraft 1.21.1 on
  NeoForge. Both sides — the server runs the engines, the client renders them.

## Licence

MIT — see [LICENSE](LICENSE). Create's MIT-licensed code is used heavily and its notice travels in
the jar; see [NOTICE.md](NOTICE.md), which also records what is deliberately *not* used, and why no
Create artwork appears anywhere in this mod.
