# The jar-in-jar unpack hack is probably unnecessary

**Status:** investigated, not changed. Raised 2026-08-22; the user is circling back to it.
Applies to **both** this repo and `create-workers`, which share the same `build.gradle` shape.

## What both repos currently do

`build.gradle` resolves Create with `transitive = false`, unpacks `META-INF/jarjar/*.jar` out of
Create's jar with a `Sync` task (`unpackCreateJij`), and puts the results on the compile classpath
as `compileOnly`. `CLAUDE.md` justifies it like this:

> Create declares Registrate / Ponder / Flywheel as Maven dependencies, but **no 1.21.1 build of any
> of them is published to a public Maven** — Create ships them jar-in-jar.

## That justification is wrong, and here is why it looked right

All three *are* published, verified by fetching their metadata on 2026-08-22:

| Artifact | Repository | Newest 1.21.1 build seen |
|---|---|---|
| `net.createmod.ponder:ponder-neoforge` | `https://maven.createmod.net` | `1.0.87+mc1.21.1` |
| `dev.engine-room.flywheel:flywheel-neoforge-1.21.1` | `https://maven.createmod.net` | `1.0.6-44` |
| `com.tterrag.registrate:Registrate` | `https://mvn.devos.one/snapshots` | `MC1.21-1.3.0+67` |

The last row is the trap. Both repos list **`https://maven.tterrag.com`** as the Registrate
repository. That host is alive — it answers 200 for
`com/tterrag/registrate/Registrate/maven-metadata.xml` — but it publishes **no MC1.21 versions at
all**. Registrate for 1.21 lives on `mvn.devos.one`. So the original conclusion was a correct
observation about the wrong repository, and the workaround it justified outlived the problem.

Note that `MC1.21-1.3.0+67` and `1.0.85+mc1.21.1` are the *exact* versions currently being unpacked
out of Create's jar, so this is not a "close enough" substitution.

## What the change would be

Delete the `createArtifact` configuration, the `unpackCreateJij` task and the `createJijJars` file
tree, add `https://mvn.devos.one/snapshots` to `repositories`, and declare:

```groovy
compileOnly "net.createmod.ponder:ponder-neoforge:${ponder_version}+mc${minecraft_version}"
compileOnly "dev.engine-room.flywheel:flywheel-neoforge-${minecraft_version}:${flywheel_version}"
compileOnly "com.tterrag.registrate:Registrate:${registrate_version}"
```

**`compileOnly` is still load-bearing** and must not be relaxed to `implementation`. The original
reason has not changed: FML loads these three out of Create's own jar at runtime, and a second copy
on the runtime classpath makes each of them load twice.

Create Crafts & Additions (MIT, the mod this was found from) avoids that by depending on Create's
**`slim` classifier**, which is the same jar without the jar-in-jar bundle — with slim, the explicit
dependencies are the only copy and can be `implementation`. **No `slim` artifact is published for
`create-1.21.1:6.0.11-300`** (checked: 404, while the plain and `sources` jars are 200), so that
route is not open to us at this version.

## Why bother

The hack works, so this is cleanup rather than a fix:

- one fewer `Sync` task in the build graph, and one less thing to explain in `CLAUDE.md`
- a `fileTree` of unpacked jars gives the IDE no sources or javadoc; real coordinates do
- `com.simibubi.create:create-1.21.1:6.0.11-300:sources` **is** published. Adding it would have
  saved decompiling Create with Vineflower to write this mod, and is worth doing regardless of
  whether the rest of this change happens.

## Risks

Low, but real: Flywheel's published versioning (`1.0.6-44`) is not identical to the version string
inside Create's bundle (`1.0.6`), so the coordinate needs pinning to whatever Create 6.0.11-300
actually ships rather than to "newest". Getting that wrong compiles fine and fails at runtime
against a different Flywheel API, which is exactly the class of breakage the current hack cannot
have. Verify with `./gradlew runClient` and `runGameTestServer`, not just `build`.
