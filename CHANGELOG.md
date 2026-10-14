# Changelog

## 0.2.0-alpha

Tested with 21 real 1.7.10 mods: Thaumcraft, Witchery, OreSpawn, Mo' Creatures, RotaryCraft,
Ars Magica 2, Carpenter's Blocks, BiblioCraft, Tinkers' Construct, Chisel, BuildCraft,
IndustrialCraft 2, Pam's HarvestCraft, Lycanite's Mobs, Iron Chests, CodeChickenCore and NEI, and
their libraries (Baubles, DragonAPI, AnimationAPI, Mantle, CustomMobSpawner). See the README for
each mod's result.

- Java 17: legacy mods can open JDK members again (setAccessible), clear `final` through
  `Field.modifiers` (potion and biome array extensions), and define classes from coremods.
- Coremods run in FML's sorted order (DragonAPI, RotaryCraft); ASM 5.2 replaces Forge's 5.0.3,
  so Java 8 classes with lambdas survive transformers (NEI).
- log4j-core's Logger, Filter and LogEvent for mods that filter logs (DragonAPI), without
  log4j-core itself.
- The 1.7.10 client has what mods expect while they load and draw: language manager, session,
  font, textures (with GL ids), block and item atlases as TextureMaps, texture stitch events,
  resource pack list, entity renderer, item renderer, render global, profiler, timer, shader
  support.
- Custom block renderers draw in place in the world, with their neighbours and tile entities
  (Carpenter's Blocks, connected textures), in every render pass the block uses.
- Textures from sprite sheets and mods' own sprite loaders (IC2); lenient animation metadata.
- GUIs mods open themselves without FML (IC2); chunks legacy code reads are views of the world.
- Chunk load, unload and data events, with mods' per-chunk data saved in the 1.20.1 chunk.
- Item names as the mods compose them (Tinkers' tool parts).
- FakePlayers, FML's server instance and effective side work as on 1.7.10, including world
  generation off the server thread and dedicated servers.
- Fixed: a deadlock when chunk generation checked where legacy mobs may spawn; every legacy block
  placed by world generation recorded a block entity; entity types registered twice under one
  name; legacy mouse buttons above 8; mod update checks hanging startup (short network timeouts).
- New `-PmodSweep` test that exercises every block, GUI, creature, item and world generator of
  the installed mods.

## 0.1.0-alpha

First public alpha, for Forge 1.20.1 (47.x).

- Runs Minecraft 1.7.10 with Forge 10.13.4.1614 inside the 1.20.1 game, on clients and dedicated
  servers. 1.7.10 and Forge come from the launcher install or are downloaded from Mojang and Forge
  on first start; 1.7.10 mods go in `retrofit/mods`.
- Legacy blocks, items, armor, tile entities, entities and spawn eggs become 1.20.1 content, with
  the mods' own textures, models, renderers, sounds and translations.
- Interaction: using, placing, breaking (with the mods' drops), item use over time, tools,
  weapons, redstone, comparators.
- Container and plain GUIs drawn by the mods' own code, with 1.20.1 item and tooltip drawing.
- Legacy entities run their own AI; natural spawning from the 1.7.10 biome spawn lists.
- Crafting through the legacy recipe list, smelting recipes, fuel; world generators; world saved
  data; stable legacy item ids per world.
- Mod packets, commands, key bindings, HUD drawing, and the FML and Forge events listed in the
  README.
- Hoppers and pipes work with legacy inventories.
- Known issues and untested mods: see the README.
