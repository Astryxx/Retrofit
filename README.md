# Retrofit

Play Minecraft 1.7.10 Forge mods inside Minecraft 1.20.1 (Forge 47).

Retrofit runs a real copy of Minecraft 1.7.10 and Forge 10.13.4 inside the 1.20.1 game, in an
isolated class loader, and bridges it to the 1.20.1 world, renderer and network. Legacy mods run
their own 1.7.10 code; their blocks, items, tile entities, GUIs, entities, recipes, world
generation, sounds, commands and packets show up as ordinary 1.20.1 content. Retrofit ships no
Mojang code and no mod code: 1.7.10 and Forge come from the player's launcher install or are
downloaded from Mojang and Forge on first start (checksum-verified), and players supply their own
1.7.10 mods. Retrofit is free, with no paid tiers.

**Status: 0.1.0-alpha.** See [Compatibility](#compatibility) for what works and what doesn't.

![A 1.20.1 world with BiblioCraft and the test mod](branding/screenshots/world-showcase.png)

## Using it

1. Install Forge 1.20.1 (47.x) and put `retrofit-forge-0.1.0-alpha.jar` in `mods/`.
2. Put 1.7.10 Forge mod jars in `retrofit/mods/` inside the game directory (created on first start).
3. Start the game. The first start downloads Minecraft 1.7.10, Forge 10.13.4.1614 and their
   libraries (a few tens of MB) unless the launcher already has them; every start then spends about
   5-10 seconds booting 1.7.10 during mod loading.

Servers: the same, on a Forge 1.20.1 server. Clients and the server need the same 1.7.10 mods;
connecting with a different set is refused with a message. Java 17.

## Compatibility

Tested with BiblioCraft 1.11.7 and the bundled test mod (`testmods/hello1710`, which exercises
each feature below), in singleplayer and on a dedicated server, from a release jar on a
production Forge install.

| Area | What works | Limits |
|---|---|---|
| Blocks | Mod blocks with metadata, shapes and collision, hardness, tools and tool tiers (as 1.20.1 tags), sounds, light, redstone and comparators, random and scheduled ticks, neighbour updates, right/left click, placing through the mod's own ItemBlock, breakBlock, drops (fortune, silk touch), experience | Shapes that depend on neighbours use their stand-alone form |
| Rendering | Plain cubes as 1.20.1 JSON models with the mods' own 1.7.10 textures; custom render types baked once; tile entity renderers and Forge item renderers every frame through a GL 1.1 emulation | Only the GL subset models need; no custom shaders |
| Tile entities | Ticking on both sides, NBT saving, description packet sync, inventories as Forge item handlers (hoppers, pipes) | |
| Items | Subtypes, durability, use on blocks and in the air, use over time (food, bows), mining speed and harvest checks, weapon damage, inventory ticks, fuel | |
| Armor | Real 1.20.1 armor with the legacy texture, armor points and onArmorTick | Dyeable and special armor models |
| GUIs | Container GUIs (FML openGui) with the mod's own drawing, slots, shift-click, drag, progress bars and buttons; plain screens; mod packets from GUIs | |
| Entities | Mod creatures and things with their own AI and pathfinding, damage both ways, death and drops, saving, the mod's own renderer, spawn eggs, natural spawning from the 1.7.10 biome lists; vanilla entities legacy code spawns (arrows, mobs) become 1.20.1 ones | Riding, leashes, legacy mobs seeing 1.20.1 mobs; 1.7.10 code assumes y >= 0 (pathfinding below 0 fails) |
| Players | Legacy code sees a real EntityPlayerMP/EntityClientPlayerMP mirror: inventory, food, health, chat, teleports, damage from legacy mobs, permission levels | Player model and render events |
| World | Mod world generators (ores, structures, generated chests), world saved data, per-world id maps (stable when mods change) | Generation is serialized |
| Recipes | Every crafting recipe (through the legacy recipe list, mods' own recipe classes included), furnace recipes in the recipe book, fuel | Legacy crafting recipes don't show in the recipe book or JEI |
| Events | FML ticks (server, world, player, client, render), login/logout/respawn/dimension, server lifecycle, PlayerInteractEvent, world load/save, key bindings and KeyInputEvent, HUD (RenderGameOverlayEvent.Post), RenderWorldLastEvent | Other Forge events (living, item tooltips, block highlight, render player...) |
| Commands | Commands registered in FMLServerStartingEvent | No tab completion |
| Sounds | Mod sounds.json and .ogg files; common 1.7.10 vanilla sound names mapped | |
| Network | SimpleNetworkWrapper, FMLEventChannel and raw custom payloads, all targets | |

Not yet tested: OreSpawn, Ars Magica 2, Mo' Creatures, Thaumcraft, Witchery, RotaryCraft,
Carpenter's Blocks. Expect parts of them to work and parts not to (mostly the limits above).
FML loads coremods as 1.7.10 did (not tested with a real one yet); a coremod's patches apply
only to the legacy side.

## Known issues

- A client sometimes stalls at "Logging in..." when connecting very soon after the game starts
  (it never answers the server's first Forge login query). It also happens without Retrofit;
  cancel and connect again.
- The first start without a 1.7.10 launcher install needs internet access to Mojang and Forge.
- FML logs "missing any signature data". Cosmetic.

## How it works

- **Boot** (`legacy-boot`, `host`): LaunchWrapper's startup loop is reimplemented for Java 17;
  FML's tweakers run in a `RetrofitLaunchClassLoader`. Java 17 fixes: Pack200 (commons-compress,
  fetched at first start), no SecurityManager, final-field writes through Unsafe, console and
  system properties kept away from the host. Log4Shell-vulnerable log4j 2.0-beta9 is never
  loaded: legacy logs go through log4j-api 2.19 to the host's SLF4J.
- **World bridge** (`legacy-bridge`, generated by `tools/gen_worlds.py`): each 1.20.1 level has a
  real 1.7.10 `WorldServer`/`WorldClient` whose block, light, redstone, entity, sound and particle
  methods call the level through `retrofit.api.HostWorld`. A real (never started) legacy
  `MinecraftServer` holds the player list and commands. Vanilla 1.7.10 blocks map to 1.20.1
  blocks through Minecraft's own data fixer, items likewise.
- **Players**: legacy code always sees a mirror player, synced around every legacy call; only the
  inventory slots legacy code changed are written back.
- **Rendering**: LWJGL 2 references go to generated stand-ins; GL11 calls are emulated and
  captured as quads the host draws with its own render types (any GL profile, shaders mods work).
  GUIs draw the same way, with 1.20.1 drawing slot items and tooltips at markers the legacy code
  leaves in the capture.
- **Network**: FML's outbound handler is redirected to one 1.20.1 channel (`retrofit:legacy`);
  inbound packets are handed to the same legacy channel.
- **Data**: a built-in client resource pack (models, textures, translations, sounds) and a
  built-in data pack (tool tags, smelting recipes, world generation, spawns) are generated from the
  legacy mods at start.
- Legacy code that throws is contained: the block, tile entity, entity or event is reported once
  in the log and skipped, never crashing the game.

## Building

JDK 17 (pinned in `gradle.properties`).

| Module | Java | Purpose |
|---|---|---|
| `legacy-boot` | 8 bytecode | Bootstrap, Java 17 fixes, transformers, GL emulation, LWJGL 2 stand-ins |
| `bridge-api` | 8 bytecode | Interfaces between the two sides (`retrofit.api`) |
| `legacy-bridge` | 8 bytecode | The legacy side of the bridge (worlds, players, GUIs, entities, events) |
| `host` | 17 | Finds or downloads 1.7.10 and Forge, builds the isolated loader, boots it |
| `forge` | 17 | The Forge 1.20.1 mod |
| `harness` | 17 | Standalone boot, render and compatibility tests |
| `testmods/hello1710` | 8 bytecode | The 1.7.10 test mod |

```
./gradlew :forge:build                 # forge/build/libs/retrofit-forge-0.1.0-alpha.jar
./gradlew :harness:run                 # boot test + compatibility report for mods in corpus/
./gradlew :forge:runClient "-PquickPlay=New World" -PworldTest   # automated world test (singleplayer)
./gradlew :forge:runServer -PworldTest                          # automated world test, dedicated server...
./gradlew :forge:runClient -PtestServer=127.0.0.1:25565 -PworldTest   # ...and its client
python -I tools/check_server_side.py   # after :harness:dumpServerSrgJar: no client-only 1.7.10 calls on servers
```

The world test places, uses, eats and breaks legacy blocks and items, crafts and smelts, opens
the test mod's and BiblioCraft's GUIs, clicks a legacy button that sends a packet, fights a legacy
mob, runs a legacy command and world generator, presses a legacy key, and saves screenshots to
`forge/run/screenshots`. The dev server (`forge/run-server`, offline mode, flat world) accepts
Mojang's EULA for itself in its build step.

Legacy-side code compiles against `run/legacy-srg/*.jar`, which `:harness:dumpSrgJar` (and
`dumpServerSrgJar`) build from your own install. They contain Mojang code: local only, never
committed or published. Real 1.7.10 mods for testing go in `corpus/` (not in git).

## License

All Rights Reserved for now; the author will choose a license.
