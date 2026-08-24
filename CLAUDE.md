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
| `vessel/PressureVesselBlockEntity` | A slim `IMultiBlockEntityContainer.Fluid`; Create's `ConnectivityHandler` does the multiblock |
| `registry/CAESFluids` | Compressed Air: a real fluid with no block and no bucket |
| `client/AirEngineRenderer` | Flywheel and piston rod; the block model itself is static |
| `client/PressureVesselCTBehaviour` | Connected textures, so a 3x3 tower is one tank and not nine crates |
| `client/CAESTooltips` | Registers into Create's own tooltip registry, so items get the native Hold-Shift treatment |
| `client/ponder/AirEngineScenes` | The Ponder scene. Its structure is generated, not authored in-game |
| `engine/IdleReason` | Why an idle engine is idle. Diagnostic only — nothing branches on it |
| `CAESConfig` | The whole balance, including the one number that ties rotation to air |
| `tools/generate_textures.py` | Every texture in the mod, including the 64-tile connected-texture sheets |
| `tools/generate_ponder.py` | The Ponder scene's structure NBT, written from a layout described in code |

## Things that will bite you

- **The engine measures the network *excluding itself*, and that is load-bearing.** An engine that
  read total capacity minus total stress would compress, see the deficit its own draw created, flip
  to generating, see the surplus its own capacity created, and flip back — once per tick, for ever.
  `networkCapacityWithoutSelf`/`networkStressWithoutSelf` subtract `lastStressApplied` and
  `lastCapacityProvided` — *the values the network actually has recorded*, not fresh calculations —
  so the subtraction is exact. `theCompressorDoesNotSeeItsOwnDraw` and
  `theMotorDoesNotSeeItsOwnCapacity` cover this and were both mutation-checked.
- **`chargeMarginStress` is not a fudge factor, it is what refuses perpetual motion.** A compressor
  needs strictly *more* spare capacity than it will draw. Without the margin, two engines back to
  back on one shaft charge each other for ever — `twoEnginesOnOneShaftCannotChargeEachOther` fails
  within a second of removing it.
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
  makes `chargeMarginStress` sufficient to refuse a self-charging loop: a motor cannot cover a
  compressor of its own tier *plus* the margin. Break the identity and
  `twoEnginesOnOneShaftCannotChargeEachOther` fails within a second.
- **Never fighting the network is the Steam Engine's flip, not a refusal to have a speed.**
  `applyNewSpeed` destroys a generator whose sign opposes a stronger network; it is perfectly happy
  with one that is merely slower. `alignDirectionWith` flips to agree with a shaft that is already
  turning, which is exactly what `SteamEngineBlockEntity` does with its rotation setting.
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
- **One height cap for every footprint, matching Create's Fluid Tank.** `getMaxLength` still receives
  the width — the hook supports per-footprint caps and an earlier version used them — but having
  three different ceilings was a rule Create does not have, and the point of this addon is to add as
  few of those as possible. `vesselsStopAtTheHeightCap` covers the cap itself.
- **Three guards are deliberately untested, and each says so at its call site.** The `getSpeed()`
  overstress check in `compress`, the `getSpeedTier() == 0` bail in `decideMode`, and the 18-engine
  ceiling. All three are unreachable at the default config or need a rig no GameTest template can
  hold. Don't delete them for having no test; do read the comment before changing them.
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
  inside a tank and nothing is logged.
- **`ConnectivityHandler.partAt` matches on block entity *type*.** Pressure Vessels therefore form
  their own multiblocks and never merge with Create's Fluid Tanks, even though both implement the
  same interface. That is why subclassing `FluidTankBlockEntity` was not necessary.

## Balance

One number ties rotation to air: `airPerStressUnit`, millibuckets moved per Stress Unit per tick.
Charging multiplies it by `roundTripEfficiency`; discharging does not. The losses live in one place.

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
