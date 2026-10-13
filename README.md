# Retrofit

Play Minecraft 1.7.10 Forge mods inside Minecraft 1.20.1.

Retrofit runs the player's **own** copy of Minecraft 1.7.10 and Forge 1.7.10 inside the
1.20.1 game. Legacy mods run against the real 1.7.10 code (coremods included), and a bridge
connects that code to the 1.20.1 world, renderer and network. Retrofit ships no Mojang code
and no mod code: players supply both, the same way Forge and launchers have always worked.
Retrofit is free, with no paid tiers.

## Status

| Milestone | State |
|---|---|
| **Boot test**: real 1.7.10 + Forge boots headless on Java 17 in an isolated loader, runs a mod's full lifecycle | ✅ Passing |
| **In-game boot**: the same boot inside a Forge 1.20.1 client during mod construction; 1.20.1 then loads normally | ✅ Passing |
| **Rendering test**: vanilla 1.7.10 `ModelPig` (real `ModelRenderer`/`Tessellator`/display lists) drawn in a 1.20.1 world through GL emulation | ✅ Passing |
| **World bridge test**: a 1.7.10 mod's block + ticking tile entity lives in a 1.20.1 world and survives save/reload | ✅ Passing |
| First real mod (BiblioCraft) | Not started |

## Layout

| Module | Java | Purpose |
|---|---|---|
| `legacy-boot` | 8 bytecode | Code that lives in the 1.7.10 world: bootstrap, Java 17 fixes, transformers |
| `host` | 17 | Finds the 1.7.10 install, builds the isolated loader, boots it (shared by harness and mod) |
| `harness` | 17 | Standalone host for fast boot testing outside Minecraft |
| `bridge-api` | 8 bytecode | Interfaces shared by both sides of the world bridge (`retrofit.api`) |
| `legacy-bridge` | 8 bytecode | Legacy half of the world bridge: a 1.7.10 `World` backed by a 1.20.1 level |
| `forge` | 17 | The Forge 1.20.1 mod: boots the legacy layer, registers legacy content, hosts legacy tile entities |
| `testmods/hello1710` | 8 bytecode | Minimal 1.7.10 mod used by the tests |

Legacy-side code must be Java 8 bytecode because Forge 1.7.10 reads classes with ASM 5.

## Requirements

- JDK 17 (the build pins it in `gradle.properties`)
- Minecraft 1.7.10 and Forge 10.13.4.1614 installed through the Minecraft launcher.
  Missing open-source libraries are downloaded into `run/legacy/.retrofit/` and checksum-verified.

## Running

Boot test outside Minecraft (fast):

```
./gradlew :harness:run
```

Inside a 1.20.1 dev client (legacy mods go in `forge/run/retrofit/mods`; the test mod is copied there):

```
./gradlew :forge:runClient
```

Either way, look for `===== BOOT OK =====` in the console or log.

World bridge test (joins an existing singleplayer world; run it twice, the second run checks the reload):

```
./gradlew :forge:runClient "-PquickPlay=New World" -PworldTest
```

Legacy-side code compiles against `run/legacy-srg/minecraft-1.7.10-forge-srg.jar`, which
`:harness:dumpSrgJar` builds from your own install (patched and deobfuscated to SRG names). It
contains Mojang code: it stays local and is never committed or published.

## Java 17 fixes so far

| Problem in 1.7.10 Forge | Fix |
|---|---|
| `LaunchWrapper` casts the system class loader to `URLClassLoader` | `LegacyBootstrap` reimplements its startup loop |
| `java.util.jar.Pack200` (removed in Java 14) unpacks Forge's binary patches | Redirected to `compat.Pack200`, backed by commons-compress on the host side |
| FML installs a `SecurityManager` | Redirected to a no-op; never install one into the host JVM |
| FML/Forge write `final` fields via `Field.modifiers` and `sun.reflect.ReflectionFactory` | `ObjectHolderRef` and `EnumHelper` redirected to `compat.FinalFields` (Unsafe + MethodHandles) |
| FML's patcher is on LaunchWrapper's transformer-exclusion list | `RetrofitLaunchClassLoader` applies the Java 17 fixes to excluded classes |
| FML replaces `System.out`/`System.err` | Redirected to no-ops; the host owns the console |

## World bridge

- Every block registered by a legacy mod becomes a 1.20.1 block (`LegacyBlock`, metadata as a
  `meta` 0-15 state property) with a block item.
- Legacy tile entities live inside one 1.20.1 block entity type (`retrofit:legacy_tile`), which
  ticks them on the server and stores their NBT under `legacy` (binary NBT is the same format in
  both versions, so it crosses as bytes).
- Legacy code sees each level as a real 1.7.10 `World` (`BridgeWorld`) whose block and tile
  entity methods call the level through `retrofit.api.HostWorld`.

## Legacy rendering

Legacy code's `org.lwjgl.opengl.GL11` calls are redirected to `retrofit.legacy.gl.GL11`, which
emulates the fixed-function state that shapes geometry (modelview stack, colour, display lists,
client vertex arrays) and captures quads into `Capture`. The host draws them with 1.20.1's own
render types. This is independent of the GL profile, so it works with Forge's early-window
core context, on macOS, and alongside shader mods. Only the GL11 subset model rendering needs
is implemented so far.

Redirects that touch vanilla classes run in `RetrofitTransformer.Late`, after Forge's binary
patches: patching already-modified bytes silently regenerates the original code.

## Logging and Log4Shell

Forge 1.7.10 ships log4j 2.0-beta9, which is vulnerable to Log4Shell (CVE-2021-44228).
Retrofit never loads it. Legacy code gets log4j-api 2.19 with a Retrofit provider
(`compat.log4j`) that forwards every logger to an SLF4J logger created by host code, so
legacy logs appear in the host's normal log. Legacy calls to `org.slf4j.LoggerFactory`
(Netty does this) are redirected the same way. The two beta9 log4j-core helpers that
Forge and vanilla call are replaced by small compat classes.

Host code must create the loggers because log4j's SLF4J binding picks a logging context
from the calling class's loader; a legacy caller would get a second, misconfigured context.

## Known issues

- FML logs "missing any signature data" because transformed classes don't carry jar signers. Cosmetic.
- Legacy blocks have no models or textures yet (they render as the missing-model block).
- Vanilla blocks look like legacy air or stone to legacy code; a full vanilla block table is next.
- Legacy tile entities only exist on the server; there is no client copy for rendering yet.
- A 1.20.1 dedicated server doesn't load the legacy layer yet.
- `HeadlessTransformer` stubs (resource reload, options, window messages) are boot-test only; the 1.20.1 bridge replaces them.
