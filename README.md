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
| **Legacy textures**: plain cube legacy blocks render with their own 1.7.10 textures as normal 1.20.1 models | ✅ Passing |
| **Vanilla blocks, items, names**: legacy code reads/places real 1.20.1 vanilla blocks; legacy items with subtypes; translations from the mods' .lang files | ✅ Passing |
| **Multiplayer**: dedicated 1.20.1 server boots the 1.7.10 server side (downloaded from Mojang); a client connects, legacy tile entity data syncs | ✅ Passing |
| **Shapes and custom rendering**: legacy collision/outline shapes, static models via RenderBlocks, tile entity renderers, item renderers (BiblioCraft: all 35 blocks render, 560/560 shapes, 520/520 TESRs, 157/157 item renders) | ✅ Passing |
| First real mod (BiblioCraft) | Loads, renders, ticks, syncs; interaction/GUIs not yet |

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
- Nothing else. Retrofit uses the launcher's Minecraft 1.7.10 and Forge 10.13.4.1614 files when
  they are installed, and otherwise downloads them from their official sources (Mojang's version
  manifest, Forge's Maven, Maven Central) into `<game dir>/retrofit/.retrofit/`, checksum-verified.
  A dedicated server downloads the 1.7.10 server jar the same way.

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

World test, singleplayer (joins an existing world; run it twice, the second run checks the reload):

```
./gradlew :forge:runClient "-PquickPlay=New World" -PworldTest
```

World test, multiplayer (the server stops itself when the test client leaves):

```
./gradlew :forge:runServer -PworldTest
./gradlew :forge:runClient -PquickPlayServer=127.0.0.1:25565 -PworldTest
```

The dev server runs in `forge/run-server`, in offline mode on a flat world, and its build step
accepts Mojang's EULA for that local server. Server-side boot alone: `./gradlew :harness:runServerSide`.

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

## Multiplayer

Both sides boot the matching side of 1.7.10 (a dedicated server uses the 1.7.10 server jar).
Everything legacy content adds is ordinary 1.20.1 content (registered blocks and items, a block
entity type, JSON models, Forge networking), so Forge's registry sync, LAN, and tools that
tunnel the normal game connection work without knowing about Retrofit.

- Legacy tile entities exist on both sides and tick on both, as in 1.7.10.
- A tile entity's 1.7.10 description packet is serialized with its own writePacketData into the
  block entity's update tag (sent with chunk data and on markBlockForUpdate) and rebuilt on the
  client for onDataPacket.
- The vanilla block table is keyed by BlockState identity, so registry sync can't stale it.
- A legacy tile entity that throws is disabled and logged; it never crashes the server or
  disconnects a player.

## Legacy assets

A built-in, always-on client resource pack (`LegacyAssetPack`) turns legacy blocks into ordinary
1.20.1 blocks for the renderer. Each block registers its icons with a recording
`IIconRegister`; `getIcon(side, meta)` then names the texture of every face, and the pack
generates a blockstate, block models (one per metadata value) and an item model from that. The
texture files are served straight from the legacy mod jars (and the player's 1.7.10 jar for
vanilla texture names) under `retrofit:blocks/legacy/...`, which a block atlas source stitches
into the 1.20.1 block atlas. Because the result is standard JSON models, it costs nothing per
frame and works with resource packs, Embeddium and shader packs.

Only legacy *mod* blocks get this treatment. Vanilla 1.7.10 blocks are never re-registered; they
will map to the real 1.20.1 blocks (see Known issues).

## Shapes, rendering and the LWJGL 2 stand-ins

- **LWJGL 2**: every legacy reference to `org.lwjgl.opengl/input/util.glu/openal` and `Sys` goes
  to a stand-in generated from the real LWJGL 2 class (`LwjglShims`): all members exist,
  implemented ones forward to `retrofit.legacy.gl`, the rest return neutral defaults.
- **Shapes**: outline and collision boxes come from the legacy block's own
  `setBlockBoundsBasedOnState` and `addCollisionBoxesToList`, per metadata value, computed in an
  isolated one-block world (`IsolatedHost`) and cached. Works on servers.
- **Static models**: blocks with custom render types are rendered once by 1.7.10's `RenderBlocks`
  into the GL emulation and become ordinary baked quads (`LegacyBakedModel`).
- **Tile entity renderers** and **Forge item renderers** run every frame through the emulation and
  are drawn with 1.20.1 render types (`LegacyBlockEntityRenderer`, `LegacyItemRenderer`), with a
  legacy render environment (camera entity, texture manager, font renderer, skin manager).
- Legacy icons of all blocks and items (vanilla included) are stitched into the 1.20.1 block
  atlas and learn their coordinates after stitching, so icon UVs hit the right sprites.
- `./gradlew :harness:run` prints a **compatibility report** for every mod in `corpus/`.

## System isolation

Legacy code shares the JVM with 1.20.1, so it may not change global state: no security manager,
no console takeover, and system properties stay local to the legacy world (`LegacySystem`;
FML 1.7.10 would otherwise switch the game's networking to IPv4-only).

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
- Legacy block names aren't translated yet (the item shows its raw translation key).
- Vanilla blocks look like legacy air or stone to legacy code; a full vanilla block table is next.
- Legacy mods' own network packets (SimpleNetworkWrapper / FMLProxyPacket) aren't carried yet;
  only description packets are.
- Legacy entities, players, GUIs/containers and item use (right-click etc.) aren't bridged yet.
- Dev environment only: a dev client connecting to a dev server sometimes stalls in Forge's login
  handshake (the server sends, the client receives nothing). It happens equally with the legacy
  layer disabled (`-PretrofitDisabled`), so it is not Retrofit; rerun if it happens.
- Item use, block right-click, GUIs/containers, legacy mod packets, entities, recipes and legacy
  events are not bridged yet.
- `HeadlessTransformer` stubs (resource reload, options, window messages) are boot-test only; the 1.20.1 bridge replaces them.
