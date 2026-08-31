# Create: CAES — repo guide

Create addon for **Minecraft 1.21.1 / NeoForge 21.1.219+ / Create 6.0+**. Compressed Air Energy
Storage: an Air Engine pumps a network's surplus stress into a Pressure Vessel as Compressed Air,
and acts as a motor to hand it back when the network runs short.

## Commands

```bash
./gradlew build              # compile + jar
./gradlew runClient          # dev client
./gradlew runServer          # dev dedicated server (needs run/eula.txt)
./gradlew runGameTestServer  # automated in-world tests -- the real check
./gradlew publishMods        # upload to CurseForge and GitHub Releases
./gradlew publishMods -PdryRun=true   # ...or rehearse it without uploading anything
python3 tools/generate_textures.py   # redraw every block/fluid texture
python3 tools/generate_ponder.py     # the Ponder structure NBT and its lang keys
python3 tools/check_models.py        # block models: rotation clearance and z-fighting
python3 tools/generate_logo.py --size 512 branding/icon-512.png
```

JDK 21 required. `gradle/gradle-daemon-jvm.properties` pins the daemon to it, so the commands work
without setting `JAVA_HOME` even when the default `java` is newer — don't delete that file, or
`./gradlew build` dies with "Could not create task ':test' ... Type T not present" on a newer JVM.

There is no unit-test suite; correctness is covered by GameTests in
`com.createcaes.test.CAESGameTests`. Run them after any change to mode selection, the stress
arithmetic, or the vessel's multiblock behaviour.

## Build quirk worth knowing

Create declares Registrate / Ponder / Flywheel as Maven dependencies, but **no 1.21.1 build of any
of them is published to a public Maven** — Create ships them jar-in-jar. So `build.gradle`:

1. resolves Create with `transitive = false`,
2. unpacks `META-INF/jarjar/*.jar` out of Create's jar (`unpackCreateJij` task),
3. puts those on the compile classpath as **`compileOnly`**.

`compileOnly` is deliberate: at runtime FML loads them from Create's own jar, and a second copy on
the runtime classpath makes each mod load twice. Catnip is not a separate artifact — it lives inside
the Ponder jar, which is where `SuperByteBuffer`, `LangBuilder` and `AngleHelper` come from.

## Distribution

Releases go out through `publishMods` (`me.modmuss50.mod-publish-plugin`), driven by
`.github/workflows/release.yml` on a `v*` tag. It needs one repository secret, `CURSEFORGE_TOKEN`;
`GITHUB_TOKEN` is provided by Actions. Things in there that are decisions, not accidents:

- **`minecraft_version_range` is `[1.21.1,1.21.2)`,** not the MDK's default `[1.21.1,1.22)`. This
  mod is written against Create 6 for 1.21.1 and reads Create's kinetic internals; the wider range
  would let it install on 1.21.4 and break there instead of refusing.
- **The changelog drives the release notes.** `publishMods` reads the `CHANGELOG.md` section whose
  heading names the current `mod_version` and fails if there isn't one — a missing entry should stop
  a release rather than ship the previous version's notes under a new number. It is wired as a lazy
  provider so an ordinary `./gradlew build` never trips over it. Both halves verified: `publishMods`
  fails on a version with no section, `build` does not.
- **The release workflow checks the tag against `mod_version`.** A tag that disagrees would publish
  the jar under the wrong number on both sites at once, and neither lets you rename a file after
  upload.
- **Both workflows re-run the generators and fail on a diff.** Every texture, the badge and the
  Ponder structure are generated, so a stale checked-in file would ship in the jar. The check stages
  first (`git add -A` then `git diff --cached`) because a bare `git diff` says nothing about a file
  the generator newly created.
- **The CurseForge token is checked with curl before anything is built.** `publishMods` uploads to two
  sites, and a missing or expired token fails at *upload* — by which point GitHub may already have
  accepted the release, leaving a version published on one site and not the other, with no way to
  rename or replace a file on either. A few seconds of curl against the upload API's cheapest
  authenticated GET turns that into a failure before anything has shipped anywhere. The status codes
  were measured against the real API rather than assumed: 200 valid, **400 malformed**, 401 absent.
  All three fail the release as a bad token; anything else fails it as "could not reach CurseForge",
  because a 502 is not a bad secret.
- **Running the release workflow by hand rehearses by default.** `workflow_dispatch` has a `dry_run`
  input defaulting to true, so a manual trigger runs the whole path — token check, build, tests,
  generator diff, changelog lookup — and writes what it *would* have uploaded instead of uploading it.
  A tag push always publishes for real. Without the default, a curious click on "Run workflow" from
  `dev` publishes whatever `mod_version` currently says, over a version already on CurseForge.
- **The `github` block sets `tagName` explicitly.** Without it the plugin invents its own tag from
  `mod_version`, so pushing `v0.1.0` produced a release filed under a second, bare `0.1.0` tag on the
  same commit — two tags per release, and the release not at the tag that triggered it. 0.1.0 shipped
  before this was noticed and still has both; `v0.1.1` onward will have one. `create-workers` has the
  same omission.
- **`archivesName` carries the Minecraft version** (`createcaes-1.21.1-0.1.0.jar`). If you change
  it, remember neither site will let you rename a file after upload.
- **`LICENSE` and `NOTICE.md` ship in the jar under `META-INF/`.** This mod leans on Create's
  MIT-licensed code heavily — `ConnectivityHandler`, `GeneratingKineticBlockEntity`, the
  `KineticNetwork` contract, the Steam Engine's tier arithmetic — and MIT wants its notice carried
  with "copies or substantial portions". A jar handed to a player is a copy. See `NOTICE.md`.
- **Commits use a repo-local identity** (`Steal-My-Mods`, the account noreply address) set in
  `.git/config`, deliberately not the global one. Don't "fix" it back. Note the initial commit
  predates that setting and is authored as the global identity.
- **CurseForge and GitHub only — Modrinth is deliberately not a destination.** The reasoning is the
  same as `create-workers`: Modrinth's Content Rules section 6.2 bans project images "created or
  derived from generative AI output" with no disclosure lane, and every pixel of this mod's art is
  chosen by `tools/generate_textures.py` and `tools/generate_logo.py`. CurseForge asks only that a
  *misleading* AI-modified showcase image carry a disclaimer, which a badge of the actual block is
  not. To restore Modrinth: redraw the art by hand, add a `modrinth_project_id`, re-add the
  `modrinth` block to `publishMods` **and** `MODRINTH_TOKEN` to `release.yml` — an empty token fails
  at upload rather than at configuration, which half-publishes a release after CurseForge has
  already accepted the jar.

## Architecture landmarks

| Path | Role |
|---|---|
| `engine/AirEngineBlockEntity` | The whole mod. Mode selection, the stress arithmetic, and the air accounting |
| `engine/AirEngineBlock` | `DirectionalKineticBlock`; FACING points at the air, the shaft is on the opposite face |
| `engine/EngineMode` | IDLE / COMPRESSING / GENERATING, persisted by ordinal and synced |
| `AirEngineBlockEntity#networkEngines` | Every engine sharing one kinetic network. What makes charging and discharging mutually exclusive |
| `vessel/PressureVesselBlockEntity` | A slim `IMultiBlockEntityContainer.Fluid`; Create's `ConnectivityHandler` does the multiblock |
| `registry/CAESFluids` | Compressed Air: a real fluid with no block and no bucket |
| `registry/CAESTags` | The `c:kinetic_energy_storage` convention, and why it is a tag rather than an API |
| `client/AirEngineVisual` | The instanced flywheel and piston rod. This is what normally draws them |
| `client/AirEngineRenderer` | The same two partials on the CPU path, for backends without instancing |
| `client/PressureVesselCTBehaviour` | Connected textures, so a 3x3 tower is one tank and not nine crates |
| `client/CAESTooltips` | Registers into Create's own tooltip registry, so items get the native Hold-Shift treatment |
| `client/ponder/AirEngineScenes` | The Ponder scene. Its structure is generated, not authored in-game |
| `engine/IdleReason` | Why an idle engine is idle. Diagnostic only — nothing branches on it |
| `CAESConfig` | The whole balance, including the one number that ties rotation to air |
| `tools/generate_textures.py` | Every texture in the mod, including the 64-tile connected-texture sheets |
| `tools/generate_ponder.py` | The Ponder scene's structure NBT, written from a layout described in code |
| `tools/check_models.py` | The only check on the block models: rotation clearance and z-fighting |
| `tools/generate_logo.py` | The badge, as a pixel sprite on the Create-family disc |

## Things that will bite you

- **The engine measures the network *excluding every engine on it*, and that is load-bearing.** An
  engine that read total capacity minus total stress would compress, see the deficit its own draw
  created, flip to generating, see the surplus its own capacity created, and flip back — once per
  tick, for ever. `networkCapacityWithoutSelf`/`networkStressWithoutSelf` subtract
  `lastStressApplied` and `lastCapacityProvided` — *the values the network actually has recorded*,
  not fresh calculations — so the subtraction is exact. `theCompressorDoesNotSeeItsOwnDraw` and
  `theMotorDoesNotSeeItsOwnCapacity` cover this and were both mutation-checked.
- **Excluding *itself* is not enough, and 0.1.2 shipped only that.** Two generators covering a third
  engine's draw close the same loop through a longer path, and `chargeMarginStress` does not refuse
  it — the margin was sized to stop one motor covering one compressor of its own tier, and a
  compressor a tier below what drives it needs a fraction of the capacity on offer. So `decideMode`
  works out `balance`: the network's capacity and stress with **every** Air Engine's contribution
  subtracted, so the figure they all test does not move when any of them acts on it. The rule that
  falls out is the one a player states — *a network is either charging or discharging, never both*.
  `oneNetworkNeverChargesAndDischargesAtOnce` is the lock; with the coalition cut back to one engine
  it reports 73 split ticks in 100, which is the reported bug exactly.
- **The rule is keyed on the kinetic network, never on the vessel, and that is deliberate.** One
  Pressure Vessel may serve engines on several networks, and standing between a network with power
  spare and a network short of it is what a vessel is *for*. `aVesselBuffersBetweenTwoNetworks` is
  the lock, and it is the test that would catch a "fix" that coordinated engines per vessel.
- **`chargeMarginStress` is now the deadband, not the defence.** It still keeps a single engine from
  flapping across the boundary, and `twoEnginesOnOneShaftCannotChargeEachOther` still passes, but
  what refuses a self-charging loop of any size is the coalition arithmetic above. Don't restore the
  old claim that the margin is what refuses perpetual motion; since 0.1.3 the margin could be zero
  and the loop would still be refused.
- **The coalition is cached, and walking it per engine per tick would not be affordable.**
  `KineticNetwork.members` is every kinetic block in the player's factory. `networkEngines()` walks
  it once per network per `COALITION_REFRESH_TICKS` and hands the result to every engine it found, so
  the other engines have nothing to do but read it; a newly placed engine does not find itself in
  its cached list, rebuilds on the spot, and tells the others about itself in the same breath.
  Between walks `sharesNetworkWith` keeps the list honest at O(the engines) — breaking a shaft splits
  a network without necessarily changing either half's id, and subtracting a departed engine's
  contribution from a total that no longer contains it reads as capacity that is not there.
- **A generator contributes at the speed it *declares*, not the speed it is spun at.** Measured, with
  two creative motors on one shaft: the 16 RPM one reads `theoretical=64` but still contributes
  `16384 × 16`, and the network total is the sum of each source at *its own* rate. So an engine that
  declared the network's speed — which this one used to — would be the only generator in the game
  whose output you could multiply with a gearbox. `ratedOutputDoesNotChangeWithNetworkSpeed` is the
  regression lock.
- **Generation copies the Steam Engine, including the arithmetic.** Efficiency comes from vessel size
  the way the Steam Engine's comes from boiler size, and picks one of four tiers: capacity is
  `rated / (16 × tier)`, so the total is `efficiency × maxStress` flat across the tier. Create's own
  curve is `1 + (eff >= 1 ? 3 : min(2, floor(eff * 4)))` and it is copied verbatim; don't "simplify"
  it to a linear ramp, the steps are deliberate.
- **The tier is a ceiling on the generated speed, not the speed itself.** `getGeneratedSpeed` returns
  `min(16 × tier, rememberedSpeed)` — the speed the network was last seen running at under someone
  else's power. Measured before this existed: an 8 RPM network jumped to 64 the instant its source
  died, changing every belt speed and multiplying every consumer's draw eightfold. Capping costs no
  coverage, because whether the engine carries the load is decided by `ratingPerRpm` and the load
  scales with speed identically. The `min` is load-bearing in the other direction too: without it a
  tier-1 engine that inherited a 64 RPM network would supply four times its rating.
  `failoverHoldsTheSpeedTheNetworkWasRunningAt` and `aFastNetworkDoesNotRaiseTheCeiling` cover the
  two halves, and each fails on its own mutation.
- **`rememberNetworkSpeed` only records while something else is driving.** While the engine is the
  source, the speed it reads is its own output; remembering that would pin the ceiling to wherever it
  happened to settle instead of to what the network is actually for.
- **Vessel size buys engines at full tilt, not a better single engine.** That is Create's boiler rule
  (`min(18, size / 4)` heat levels shared across attached engines) and
  `PressureVesselBlockEntity#getEngineEfficiency` is the same shape. The count comes from a lazy-tick
  scan of the multiblock's outward faces — interior blocks have no face an engine could attach to,
  which is what keeps it cheap.
- **Impact and capacity are deliberately the same number at the same tier.** That identity is what
  makes `chargeMarginStress` sufficient at the *equal-tier* pair the margin was sized for: a motor
  cannot cover a compressor of its own tier *plus* the margin. Break the identity and
  `twoEnginesOnOneShaftCannotChargeEachOther` fails within a second. Note what it does not buy —
  mismatched tiers walk straight past it, which is half of why the coalition rule exists.
- **Never fighting the network is the Steam Engine's flip, not a refusal to have a speed.**
  `applyNewSpeed` destroys a generator whose sign opposes a stronger network; it is perfectly happy
  with one that is merely slower. `alignDirectionWith` flips to agree with a shaft that is already
  turning, which is exactly what `SteamEngineBlockEntity` does with its rotation setting.
- **The vessel only tells its neighbours when the comparator reading has actually moved, and that
  guard is the difference between free and being the most expensive thing here.** `SmartFluidTank`
  fires its callback on every fill and drain, and an engine moves air on *every* tick it runs — even
  the smallest legal setup shifts ~25mB — so `onFluidStackChanged` runs 20 times a second. Its sweep
  is a `getBlockEntity` plus an `updateNeighbourForOutputSignal` for every block of the multiblock,
  and the latter is itself four neighbour blockstate reads. Measured over 100 ticks of steady
  compression on a 3x3x5 vessel: 4,500 lookups, 4,500 neighbour updates, and the redstone level
  changed **zero** times — about 40us a tick, or ~260us at the 3x3x32 cap, to publish a number that
  did not move. Create's Fluid Tank runs the identical loop unguarded and is right to; a tank's
  contents change when a pipe moves fluid, not on a timer. Two things to know before touching it:
  `BlockEntity.setChanged` already calls `updateNeighbourForOutputSignal` for its *own* position, so
  the controller's neighbours are told regardless and telling the **rest** of the multiblock is the
  sweep's only job — which is why `aVesselKeepsTellingItsComparators` puts its comparator beside a
  non-controller course, and why the same test beside the controller passed with the sweep deleted.
  And a comparator's `POWERED` is not a probe for this: `DiodeBlock.tick` powers an unpowered diode on
  any scheduled tick and only then schedules another to turn it off again, so it flickers true for
  unrelated reasons. Assert on `ComparatorBlockEntity.output`.
- **Dropping out of GENERATING is expensive, so there is a deadband on the way out as well as in.**
  It takes `getGeneratedSpeed()` to zero, and with nothing else driving the shaft that is
  `applyNewSpeed`'s costly branch: `detachKinetics` sends `RotationPropagator.propagateMissingSource`
  over the whole network with a `sendData` per member, and the next attempt runs `attachKinetics` to
  rebuild it, while `refreshKineticContribution` walks every member again through
  `KineticNetwork.calculateStress`. One flip costs O(the player's factory). A vessel taking in air
  more slowly than the engine spent it flipped **30 times in 100 ticks** — measured — so
  `NO_AIR_COOLDOWN_TICKS` now holds it off for a second. `chargeMarginStress` is the deadband between
  compressing and generating; this is the one between generating and giving up, and the mod needs
  both. `aTrickleFedEngineDoesNotFlapItsMode` is the lock and fails without it.
- **`generate` asks the supply before it takes anything, and that is a balance rule, not a style
  choice.** Draining short means the engine turned for air it never got, and `setMode` clears
  `airBuffer` on the way out, so the shortfall was *forgiven* rather than carried — on a trickle,
  measured, an engine running mostly on air nothing paid for. So a stroke it cannot afford is not
  started, and the dregs stay in the vessel until there are enough for a whole one. A vessel
  therefore bottoms out a few mB above empty, which is correct rather than a rounding bug.
  `anEngineNeverDrainsAPartialStroke` is the lock; note the flap budget alone does **not** catch this,
  because restoring the partial drain while still arming the cooldown keeps the mode changes in
  budget and only the air goes missing.
- **The engine scan walks the six outward face slabs, not every face of every shell block.**
  `2w² + 4wh` positions, all of which genuinely touch the outside, instead of `6 ×` the shell with
  two thirds thrown away again by a `contains` test — 402 against 1,548 on a 3x3x32 vessel, ten times
  a second. Walking slabs also means the inward direction is known from which slab you are on, so the
  only thing left to check is the engine's facing and `contains` is gone entirely. The probe is a
  blockstate read, not `getBlockEntity`, which resolves pending block entities and will create one,
  for a position that is almost never an engine. `enginesAreCountedOnEveryFace` covers the caps, the
  walls and a decoy engine pointing the wrong way; an off-by-one in any slab silently *raises* a
  vessel's efficiency, so it would not otherwise announce itself.
- **Three engines cannot sit in one straight line, which is why two rigs use cogwheels.** An engine
  puts its shaft on one face only, so a shaft line reaches exactly the two engines at its ends. A
  third hangs off a pair of meshed cogwheels — same axis, offset by one perpendicular to it — which
  is what `oneNetworkNeverChargesAndDischargesAtOnce` and `twoCompressorsShareOneMotorsSurplus` do.
  A shaft laid along X above an engine facing DOWN does *not* connect to it, and the symptom is a
  silent `NOT_TURNING` rather than anything that looks like a rig error.
- **A rotating block needs a Flywheel visual as well as a renderer, and the two must agree.** Create
  registers both for everything that turns — `STEAM_ENGINE` has `SteamEngineVisual` next to
  `SteamEngineRenderer`, and so do `POWERED_SHAFT` and `FLYWHEEL`. 0.1.1 shipped only the renderer, so
  every engine in view had both partials transformed vertex by vertex on the CPU every frame instead
  of being uploaded once and instanced. `AirEngineVisual` is a line-for-line mirror of
  `AirEngineRenderer`'s geometry on purpose: `skipVanillaRender` defaults to true, so the visual draws
  the engine with the backend on and the renderer draws it with the backend off, and a player must
  not be able to tell which. **Change one and change the other.** Registration is by hand
  (`SimpleBlockEntityVisualizer.builder(...).apply()`) because this mod does not use Registrate's
  `.visual(...)`, and a visual that fails to register is silent — hence the startup check in
  `CAESClient.registerVisuals`. Nothing else can catch it: a dedicated server builds no visuals, so
  no GameTest sees this at all, and the instanced path was checked the only way it can be — by eye in
  `runClient`, block and item, flywheel and rod. If you touch either path, note that the default
  backend is instancing, so what you are looking at is `AirEngineVisual`; `/flywheel backend` swaps to
  the off backend to put `AirEngineRenderer` on screen instead, and both need looking at.
- **Never schedule a GameTest callback from inside a GameTest callback.**
  `GameTestInfo.tickInternal` runs the test body *before* it takes its iterator over the schedule, so
  scheduling from the body is safe — but a callback runs *during* that iteration, and enough new
  entries rehash the map underneath it. The failure is a `NullPointerException` deep in fastutil, it
  crashes the whole test server rather than failing one test, and it is intermittent: a hundred
  nested `runAfterDelay` calls crashed three runs in ten. Several of the older tests nest one or two
  and get away with it because two puts do not rehash. Schedule flat.
- **The engine's efficiency is synced, not recomputed on the client.** Half of what feeds it — the
  fluid capability in front — does not exist client-side, and the goggle overlay reads the tier from
  the client's copy. Compute it once per server tick into the field and let NBT carry it.
- **The engine warms up for 5 ticks before deciding anything.** A freshly placed block has not been
  found by the rotation propagator, so it reads as having no source and no network capacity — which
  is indistinguishable from being the only possible source. Without the warm-up, an engine placed
  beside a running motor and a charged vessel spends its first tick generating.
- **`hasSomethingToDrive()` is why a charged engine in a storage room does not empty itself.** Being
  the sole source is not enough; there has to be a kinetic block on the shaft side that would take
  the rotation.
- **Compression uses `getSpeed()`, generation uses `getGeneratedSpeed()`.** `getSpeed()` returns 0
  on an overstressed network, which is the point: a stalled network does no compressing. In steady
  state that guard is unobservable — the charge test excludes the engine's own draw, so a compressor
  cannot be what overstressed its own network — and it only bites on the tick another machine starts
  up. Don't delete it for being untested; it is defence for a transient, and that is noted at the
  call site.
- **A Create block entity can be a source *and* a member at the same time.** `KineticNetwork#add`
  puts every block into `members` and additionally into `sources` when `isSource()`. That is what
  makes one dual-mode block possible at all, rather than needing two.
- **Air is fractional, tanks are integral.** Rates come out as a few mB per tick; `airBuffer` carries
  the remainder between ticks. Reset it on every mode change or a compressor's leftovers get spent
  as a motor's first drink.
- **Compressed Air has no block and no bucket, on purpose.** `BaseFlowingFluid#createLegacyBlock`
  returns air when no block is supplied, which is correct here — a gas at pressure only exists inside
  something built to hold it. Don't add a bucket to make testing easier; the GameTests fill the tank
  directly.
- **Partials and the block model are both authored pointing up.** The blockstate uses vanilla's
  `end_rod` rotation convention and `AirEngineRenderer` swings the partials the same way, so one
  orientation serves all six facings. Author a new partial pointing any other way and it will be
  right for exactly one facing. The renderer negates the spin for the three negative facings,
  because the model's local up axis points along the facing rather than along the positive axis
  Create measures rotation about.
- **A rotating partial needs an open band to turn in, and a silhouette that does not change as it
  turns.** Create obeys both rules and 0.1.0 obeyed neither. `millstone/inner.json` is four bars at
  0/45/90/135 degrees — flat radius 9.00, *sweep* radius 9.12, so the outline barely moves — turning
  in a band where `millstone/block.json` has no geometry at all; the Flywheel takes the other route
  and is a real OBJ circle in open air. Ours was a plain four-arm cross plus a 12x12 web turning at
  `y 2..6` inside a housing that was one solid box `[2,0,2]→[14,16,14]`, wall at radius 6. Measured:
  sweep radius 8.49 for the web and 8.54 for the arms against that wall, so ~2.5px of web corner and
  arm passed *through* the housing on every revolution, and the arms left the block altogether. Both
  halves are load-bearing — the housing is now a `y 0..2` foot and a `y 6..16` body with `y 2..6` open
  air, and the wheel is four bars at 0/45/90/135 with a worst reach of 7.16 inside the block's 8.
  Fixing only the silhouette still grinds; opening only the band still z-fights.
- **What z-fights is coplanar and same-facing, not overlapping — and "draws the same pixels" includes
  the uv.** Interpenetrating boxes are fine; the depth buffer sorts them out, which is why the axle
  sits inside its bearings and the piston rod enters the housing without either being a bug. What is
  never fine is two quads on one plane facing one way that draw *different* pixels. The trap is that
  sharing a texture is not enough. The first fix here put the web and all four spokes at `y 3..5`, so
  their top faces were coincident over 32–64 square units, and because the web samples
  `air_engine_flywheel` at `uv [0,0,16,16]` (the hub bore) while the spokes sample it at
  `uv [1,1,6,6]` (plain brass), they fought as visibly as two different textures would — which is
  what was still wrong after the housing was opened up. So every layer now has its own planes: spokes
  `y 3..5`, web `y 3.25..4.75`, counterweight `y 3.5..4.5`, and the spokes were narrowed to 3 wide to
  lift their `z` planes off the axle's 6 and 10. Create insets its millstone web the same way and for
  the same reason — bars `y 6..12`, web `y 6.5..11.5`. Where layers do overlap the thinner one is
  enclosed in the thicker, so it is hidden rather than fighting, which is also what makes the wheel
  read as machined rather than flat.
- **The piston rod stops at `y 13`, not `y 14`, and the stroke is why.** `PISTON_THROW` is 2px, so a
  rod modelled at `y 10..14` tops out at exactly 16 — the housing's own up face, same normal,
  different texture. It only shows when there is nothing above the engine to cull that face, which
  for a pipe-fed engine is the normal case.
- **A block model allows one rotation per element, and only ±22.5 or ±45 degrees.** Which is why the
  diagonal spokes are the *same two bars* turned 45 rather than one bar turned 45 and another turned
  135, and why the counterweight sits at 22.5. Create's millstone does the identical trick. Anything
  else needs an OBJ.
- **`air_engine_side` maps 1:1 onto world height and is split across two elements.** The body samples
  rows 0..9 (`y 6..16`) and the foot rows 14..15 (`y 0..2`); rows 10..13 are the open band and are
  never drawn by any face. So the brass lips at rows 9 and 14 are load-bearing pixels — move the
  recess and you must move both the pixels in `engine_side()` and the `uv` on four faces of each
  element, or the housing's shading slides off its own geometry with nothing to warn you.
- **`models/item/air_engine.json` is standalone and inlines all ten elements.** It used to parent the
  block model; now that the housing has a band cut out of it, parenting would show the item as a foot
  and a body with an empty gap between them. Create's `millstone/item.json` inlines its spinning inner
  wheel for exactly this reason. It has to be rebuilt whenever the housing or either partial moves,
  and nothing enforces that but the comment at the top of it.
- **The badge is pixel art blown up by a whole number, and that is the whole style.** The subject is
  drawn on a 16x20 grid at `tools/generate_logo.py`'s own resolution and scaled by `SPRITE_SCALE`,
  which must stay an integer or the pixels come out rectangular — which is why `--size` insists on a
  multiple of 256. 0.1.0's badge was smooth vector shapes with a real circular gauge, and it was the
  only one of the three sibling addons that did not look like it came out of the same game. The disc,
  the ring, the 46px grid, the 6px stroke and the shadow offset are all the numbers `create-workers`
  and `create-gravity-batteries` use; keep them in step, because the point of them is that the three
  sit together on a mods list.
- **`check_fits` is there because overrunning the disc does not look like a bug.** Growing the sprite
  a row, or nudging `SPRITE_SCALE` up one, is the obvious way to make the badge bolder, and the
  failure is that `render()` silently clips the corner nearest the rim flat — which reads as a design
  choice. So the far corner of every opaque cell is measured against `RADIUS - RING` before anything
  is drawn. At scale 9 the stroked art reaches 108.7 of 115; scale 10 reaches 120.1 and the guard
  fires, which was checked rather than assumed.
- **None of this is testable by GameTest, and `tools/check_models.py` is the substitute.** A dedicated
  server loads no models at all, and the client logs a *perfectly clean* startup for a model that
  grinds through itself — it is only wrong to look at. The tool checks the two things arithmetic can
  see: that every rotating box turns clear of the housing and stays inside the block, and that no two
  same-facing coplanar quads draw different pixels. All three of the faults above were caught by it
  and it fails on each of them being put back. It cannot tell you the engine *looks* right — only
  `./gradlew runClient` can, and the item in hand needs looking at separately from the block.
- **One height cap for every footprint, matching Create's Fluid Tank.** `getMaxLength` still receives
  the width — the hook supports per-footprint caps and an earlier version used them — but having
  three different ceilings was a rule Create does not have, and the point of this addon is to add as
  few of those as possible. `vesselsStopAtTheHeightCap` covers the cap itself.
- **Six guards are deliberately untested, and each says so at its call site.** The `getSpeed()`
  overstress check in `compress`, the `getSpeedTier() == 0` bail in `decideMode`, the 18-engine
  ceiling, the `lastComparatorLevel` invalidation in `refreshComparators`, the `balance < 0` half of
  `decideMode`'s generating test, and the refusing half of `fitsInChargingAllocation`. Most are
  unreachable at the default config or need a rig no GameTest template can hold; the last two both
  need a source whose surplus a couple of engines can exhaust, and a creative motor's is effectively
  unbounded. Deleting any of them leaves all 37 tests green — each was mutation-checked to confirm
  it. Don't delete them for having no test; do read the comment before changing them.
- **Performance is asserted as counts of work, never as elapsed time.** A count is a property of the
  code and comes out identical on a laptop and on a CI runner; a microsecond budget is a property of
  whatever ran the build, and would either flake or be set so loose it caught nothing. So
  `PressureVesselBlockEntity#getComparatorSweeps` and `AirEngineBlockEntity#getModeChanges` exist as
  diagnostics, and `aRunningVesselBarelyEverSweepsItsParts`,
  `aTrickleFedEngineDoesNotFlapItsMode`, `anEmptyVesselNeverStartsGenerating` and
  `anEngineWillNotStartAStrokeItCannotPayFor` budget them. Both counters also see what nothing else
  can: a sweep whose reading did not move has no outward sign at all, and a mode taken and given back
  inside one tick is invisible to a per-tick sample. Add a counter rather than a stopwatch when the
  next hot path turns up.
- **The 18-engine ceiling is Create's, not ours.** `min(18, size / 4)` is the boiler's rule and
  `getEngineEfficiency` is `min(18, size / 9)` for the same reason: without it a tall enough vessel
  runs an unbounded number of engines. It only binds past ~162 blocks, which is more than a GameTest
  template can hold, so it is **not covered by a test** — change it carefully.
- **The connected-texture sheet is generated from a port of Create's own index function.** 64 tiles,
  and which one a face gets is decided at render time by `AllCTTypes.OMNIDIRECTIONAL`. `ct_index` in
  `tools/generate_textures.py` is that function transcribed; the sheet is built by enumerating all
  256 neighbourhoods, asking it, and drawing what they agree on. Getting the port wrong makes the
  sheet wrong in exactly the same way the renderer is, which is the only kind of wrong that stays
  invisible — so it asserts when two neighbourhoods disagree about a tile's edges, and 17 of the 64
  tiles are legitimately unreachable.
- **Faces buried inside the multiblock still need their CT context built.**
  `buildContextForOccludedDirections` returning true is what stops every course growing a border
  along its top edge.
- **Everything about a Ponder scene fails silently.** A missing structure, a bad block state and a
  missing lang key all produce a *completely clean* client log at startup and only go wrong when a
  player opens the scene. Two guards, because one cannot cover both halves:
  `thePonderStructureIsValid` parses the .nbt the way the game will, checks every palette entry
  against the real registry, and insists the vessel arrives already formed; `CAESClient.checkPonderScenes`
  compiles the scenes headlessly at startup in dev and reports any text Ponder wants a key for that
  I18n has not got. Neither can tell you the scene *looks* right — only opening it can.
- **Nothing in a ponder scene rotates by itself, and a still scene looks finished.** A ponder level
  is client-side, and every path that assigns a kinetic speed is server-gated: `KineticBlockEntity`
  only calls `attachKinetics()` under `!isClientSide`, and `RotationPropagator` bails the same way,
  so a shaft in a scene never acquires a speed. Create fakes it — `setKineticSpeed` writes the
  `Speed` float the renderer reads straight back off the block entity — and that method lives on
  `CreateSceneBuilder`, not on Ponder's own `SceneBuilder`, which is why every Create scene opens by
  wrapping the builder it is handed. `AirEngineScenes` did not, and shipped 0.1.0 as a still life
  with captions: motor, shaft and flywheel all frozen, so pulling the motor out changed nothing on
  screen while the text narrated a failover. The engine's mode has the same shape — `tick()` returns
  early on the client, so the scene writes `Mode` by hand with `modifyBlockEntityNBT`, and the piston
  is frozen without it. Unlike Create's scenes ours needs no `setKineticSpeed(everywhere, 0)` first:
  their structures are captured out of running worlds and arrive carrying real speeds, ours is
  generated and carries no `Speed` tag at all.
- **COMPRESSING and GENERATING are visually identical, and that is not yet decided.**
  `AirEngineRenderer` distinguishes only IDLE from not — the piston freezes, the flywheel is
  shaft-locked either way — so without goggles a player cannot see which direction a running engine
  is working in. The scene leans on text and a green/red outline. See the note in Balance about
  `IdleReason`: diagnostics on the goggles is this mod's established answer, so this may be correct
  as it stands rather than a gap.
- **Ponder text is not the string you pass to `.text(...)`.** That string is only the datagen default.
  Ponder resolves every line through I18n against a key it derives itself —
  `<namespace>.ponder.<sceneId>.header` and `.text_N`, numbered from one in call order — and shows
  the raw key when it is missing. Create generates these in datagen; `tools/generate_ponder.py` reads
  them back out of the scene source instead, and asserts it matched every `.text(` it can find.
- **A ponder level never forms a multiblock.** Forming one is server-side work and a ponder level is
  client-side, so a Pressure Vessel restored without a `Controller` pointer in its block entity data
  is eight separate one-block tanks whose textures will not connect. Create's structures are captured
  out of real worlds where the multiblock had already formed; ours is generated, so
  `generate_ponder.py` writes `Controller`, `Size`, `Height` and `LastKnownPos` by hand. Ponder
  re-anchors the controller by the offset between `LastKnownPos` and where the block actually lands,
  so the two only have to agree with each other.
- **Textures outside `textures/block/` are not in the block atlas.** The vanilla atlas has a directory
  source for `block/` and nothing else, so the Compressed Air sprites need explicit entries in
  `assets/minecraft/atlases/blocks.json` — which is merged across mods, and is exactly why Create
  ships one listing its own fluids. A fluid whose sprite is not stitched renders as missing texture
  inside a tank and nothing is logged. Note *whose* tank: the sprites and the translucent render
  layer are there for Create's Fluid Tank and its pipes, both of which draw their contents and both
  of which Compressed Air legitimately reaches — a pipe-fed engine is a supported setup. The
  Pressure Vessel itself never draws anything.
- **The Pressure Vessel is opaque, unlike Create's Fluid Tank, and that is a decision.** A plain
  `cube_bottom_top` with opaque caps and sides, no window, and no block entity renderer registered —
  so it never draws what it holds. A gas at pressure has nothing to look at, which is the same
  reasoning that gives Compressed Air no block and no bucket. Consequences worth knowing before
  "fixing" either: there is no point seeding `TankContent` into the Ponder structure, so the scene's
  air story is text only and that is not a gap in the scene. The fill level is surfaced where this
  mod surfaces everything else — a goggle percentage and a comparator level, both off
  `getFillState`.
- **`ConnectivityHandler.partAt` matches on block entity *type*.** Pressure Vessels therefore form
  their own multiblocks and never merge with Create's Fluid Tanks, even though both implement the
  same interface. That is why subclassing `FluidTankBlockEntity` was not necessary.

## The kinetic storage convention

`c:kinetic_energy_storage` is a cross-mod block tag meaning **the capacity this block supplies to a
kinetic network is drawn from a store it filled earlier, not generated.** An engine refuses to
compress on capacity supplied by anything in it. Create: Gravity Batteries honours the same tag from
its 0.1.1, and the two mods were written against each other to settle it.

- **It generalises the coalition, it does not replace it.** `networkEngines()` can read a peer's
  fields — `wantsToCompress`, its exact draw — which is what lets several compressors *share* one
  surplus rather than the first one taking it. A tag cannot say any of that, so
  `foreignStoredCapacityOnNetwork()` skips Air Engines entirely and the coalition goes on handling
  them. Counting them in both places subtracts them twice, which reads as a deficit that is not there;
  `anEnginesOwnAirIsNotCountedAsBorrowedCapacity` is the lock, verified against that mutation.
- **It is a tag because only one bit has to cross the mod boundary.** Create already exposes
  `KineticNetwork.sources` and `getActualCapacityOf`, so the amount needs no cooperation from the
  other mod — and `getActualCapacityOf` multiplies by `getGeneratedSpeed()`, which is zero for a store
  that is not currently spending, so the runtime half is answered too. A shared API artifact or a
  NeoForge capability would both need a common class on the classpath and would only re-report a
  number Create already gives you. The `c` namespace matters for the same reason the tag does: nobody
  owns it, so **a pack author can add a third mod's block with a datapack** and fix an interaction
  neither author has heard of.
- **It comes out of what the charging allocation may spend, never out of `balance` itself.**
  `fitsInChargingAllocation(coalition, balance - borrowed)`. A discharging store genuinely is holding
  the network up, so the `wantsToGenerate` test must go on counting it — subtract it there and a
  network one Gravity Battery is comfortably covering reads as a deficit to every engine on the shaft,
  and they all start generating against a shortfall that does not exist.
- **Tag the kinetic source, not the storage.** The Air Engine is in the tag; the Pressure Vessel,
  which holds the air but turns nothing, is not. `theAirEngineDeclaresItselfAsKineticStorage` asserts
  both.
- **Declaring is a separate job from honouring, and only declaring is invisible when it breaks.** The
  refusal above protects this mod's air; being *in* the tag is what protects everybody else's, and
  nothing in this repo would fail if the json went missing or the name drifted. Hence a test that
  spells `c:kinetic_energy_storage` out as a literal rather than reading it from `CAESTags` — a
  consistent rename across the constant and the file passes every behaviour test and silently stops
  the mod composing with anything.
- **There is no cross-mod GameTest.** Standing an Air Engine and a Gravity Battery on one shaft needs
  both mods in one dev runtime, so a cross-repo build dependency and a CI job that cannot run until
  the sibling has published. The guarantee is split instead: each mod proves it declares itself, and
  each proves it refuses tagged capacity. Two mods passing both compose, and that scales to mods that
  do not exist yet, which an integration test against one named sibling does not.

## Balance

One number ties rotation to air: `airPerStressUnit`, millibuckets moved per Stress Unit per tick.
Charging multiplies it by `roundTripEfficiency`; discharging does not. The losses live in one place.

**`roundTripEfficiency` defaults to 1.0, and that is considered rather than missing.** It was 0.7. A
buffer's whole value is letting you size generation to average load rather than peak, so taxing the
round trip charges for the feature; Create models no losses anywhere, its Steam Engine "efficiency"
being a boiler *allocation* ratio rather than waste; and FE mods reach the same answer, storing
losslessly and putting their losses in transmission where the player is making a decision. The
argument specific to this mod is the strongest one: the vessel-size efficiency already charges a
player for building small, so a flat round-trip loss on top taxed the same player twice. What refuses
an engine compressing against a store is `foreignStoredCapacityOnNetwork()` and the coalition, at any
setting — see *The kinetic storage convention*.

Efficiency is `min(1, vesselBlocks / (blocksPerEngine × attachedEngines))`. The `min` is what makes
the two headline figures fall out, both measured rather than derived:

- **Ceiling: `maxStress / blocksPerEngine` = ~910su per block**, whatever the engine count. Past nine
  blocks per engine efficiency is already 1.0 and further blocks buy duration only.
- **Runtime: `10,000 × blocks ÷ load` seconds**, load-scaled with a floor at `idleAirDraw` of the
  rating. Run a vessel at its ceiling and you get 11 seconds regardless of size; run it at a tenth
  and you get 110.

An engine drawing through a pipe has no vessel size to read and gets a flat `pipeFedEfficiency` of
0.125, the same allowance Create gives an unheated boiler. Bolting it to a vessel is meant to be
worth doing.

One height cap for every footprint (`vesselMaxHeight`, 32 by default), the same way Create's Fluid
Tank does it. An earlier version had 8 / 12 / 16 per footprint; that was removed as a rule Create
does not have.

Several engines on one network share one balance rather than each measuring their own. They all
walk the same coalition in the same position order and run the same arithmetic, so they allocate the
surplus consistently without any of them being in charge and without a packet passing between them.

**On throttling the compressor.** A compressor's draw is fixed by its tier and the network speed, so
a tier-4 engine on a 32 RPM shaft needs 4,096su spare before it starts. That was considered and
rejected as a problem to solve: 4,096su *is* the machine's stress impact, and needing your impact
spare is how every Create machine works. Throttling it to fit the available headroom would mean a
stress impact that changes every tick — the least Create-like option available — and would pin the
stress gauge at 100%. It would also trade the hard refusal in
`twoEnginesOnOneShaftCannotChargeEachOther` for a merely-lossy loop. What was done instead is
`IdleReason`, which puts the number on the goggles.

## Conventions

Tabs for indentation, matching Create's own style. Registry classes are `CAES*` under `registry/`.
Nothing is committed without explicit instruction.
