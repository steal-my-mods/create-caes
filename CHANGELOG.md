# Changelog

## 0.1.1

Fixes to what the Air Engine looks like and to the Ponder scene that explains it. Nothing about how
it works changed, and an 0.1.0 world loads unchanged.

- **The flywheel no longer grinds through its own housing.** The housing was one solid column with
  the wheel turning inside its wall: about 2.5px of every arm passed through the casing on each
  revolution, and the arm corners left the block altogether. The housing is now a foot and a body
  with an open band between them for the wheel to turn in, and the wheel is four bars whose
  silhouette barely changes as it turns -- the two rules Create's own Millstone follows. Each layer
  of the wheel sits on its own plane, so the hub no longer flickers against the spokes, and the
  housing's brass band is real geometry rather than paint.
- **The Air Engine item has its own model.** With a band cut out of the housing, inheriting the
  block model showed the item in hand as a foot and a body with an empty gap between them.
- **The piston rod stops a pixel short of the top**, so a full stroke no longer fights the housing's
  own face on an engine with nothing stacked above it -- which for a pipe-fed engine is the normal
  case.
- **The Ponder scene moves.** The motor, the shaft and the flywheel were frozen for the whole scene,
  so pulling the motor out changed nothing on screen while the text narrated a failover, and the
  piston never stroked. They now turn, the engine holds the speed across the handover the way it
  really does, and its mode flips on the beat the caption describes.
- **A new badge**, redrawn as pixel art on the same disc its sibling addons use, so the mod looks
  like it came out of the game it is for.

## 0.1.0

First release. Compressed Air Energy Storage for Create.

- **Air Engine** — a dual-mode kinetic block. It compresses air into a vessel while its network has
  stress capacity to spare, and acts as a motor to supply capacity when the rest of the
  network cannot carry its own load. It picks a mode from the network balance measured *excluding
  its own contribution*, so it settles rather than oscillating, and a configurable margin keeps two
  engines on one shaft from charging each other.
- **Generation follows Create's Steam Engine.** Vessel size decides an efficiency the way boiler size
  does, efficiency picks one of four speed tiers (16/32/48/64 RPM), and the total stress supplied is
  flat across the tier — so gearing a network up does not multiply what an engine is worth. Engines
  sharing one vessel split it between them, as Steam Engines split a boiler. An engine joins a shaft
  that is already turning rather than fighting it.
- **Pressure Vessel** — a multiblock that stores Compressed Air, formed and split by Create's own
  connectivity handler. Footprints up to 3x3 and up to 32 tall, one cap for every width as with
  Create's Fluid Tank. Placing against a wide vessel lays a whole course at once. Capacity scales with the number of blocks; fill level reads out on
  a comparator and under goggles. Stacked vessels connect their textures and render as one tank.
- **Ponder scene** for the Air Engine, with its structure and lang generated from one place, and Create's native item tooltips — summary, Hold Shift for
  behaviours, and the stress bar — on both blocks.
- **An idle engine says why.** Goggles report no supply / empty / full / not turning / not enough
  spare, and for the last one it quotes what it needs against what is going spare.
- **Compressed Air** — a real fluid, so Create's pipes, pumps and gauges all work on it. It has no
  bucket and no block: it only exists inside something built to hold it.
