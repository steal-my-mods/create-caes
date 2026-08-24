# Third-party notices

Create: CAES is MIT licensed; see [LICENSE](LICENSE). This file records the third-party code it is
built from, and the notices that code's licence requires be carried along with it.

## Create

Create is split-licensed: its **code is MIT**, and everything under its `assets/` is **All Rights
Reserved**. Only the MIT half is used here, and it is used heavily — this mod is written against
Create's own extension points rather than around them:

- `PressureVesselBlockEntity` implements Create's `IMultiBlockEntityContainer.Fluid` and is formed
  and split by Create's `ConnectivityHandler`. The 3x3 footprint rule, the controller election and
  the fluid merge on join are all Create's, not this mod's.
- `AirEngineBlockEntity` extends `GeneratingKineticBlockEntity`, and its stress arithmetic is
  written against the contract in `KineticNetwork` — capacity and impact are per-RPM ratings
  multiplied by speed, and a block may be both a source and a member at once.
- `AirEngineRenderer`'s orientation transform (centre, swing to the facing, animate in the model's
  own frame) is the idiom from Create's `SteamEngineRenderer`.

That is enough of Create's MIT code that its notice travels with this mod:

> MIT License
>
> Copyright (c) The Create Team / The Creators of Create
>
> Permission is hereby granted, free of charge, to any person obtaining a copy
> of this software and associated documentation files (the "Software"), to deal
> in the Software without restriction, including without limitation the rights
> to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
> copies of the Software, and to permit persons to whom the Software is
> furnished to do so, subject to the following conditions:
>
> The above copyright notice and this permission notice shall be included in all
> copies or substantial portions of the Software.
>
> THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
> IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
> FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
> AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
> LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
> OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
> SOFTWARE.

Two things this mod deliberately does **not** do, both of which would need more than the notice
above:

- **It does not redistribute Create.** Create is resolved without transitives and nothing from it is
  bundled into the jar — at runtime the loader uses the player's own copy. This is also why the mod
  declares Create as a required dependency rather than shipping it.
- **It does not use any Create asset.** Every texture, model and icon here is drawn by
  `tools/generate_textures.py` and `tools/generate_logo.py` from geometry described in those files.
  No model in `assets/createcaes/` parents or textures off a `create:` resource. The badge follows
  the visual convention Create addons share — a white-ringed circle of blue graph paper with the
  mod's headline object in front — but no pixel of it comes from Create's artwork.

## Create: Connected

Create: Connected has a Kinetic Battery that occupies the same design space as the Air Engine, and
it was **read about but not read**: that mod is **AGPL-3.0-or-later**, which this MIT-licensed mod
could not carry. What informed the design here came from its public feature documentation — that a
kinetic buffer should discharge only as much stress as the network is short, and should stay out of
the way while other sources are covering the load — not from its source. Both of those behaviours
were then derived independently from Create's own `KineticNetwork`. Do not paste code from that
project into this one.

## Minecraft and NeoForge

Not redistributed either, and not linked into the jar. Mappings are Parchment, used at build time
only.
