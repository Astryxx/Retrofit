# Changelog

## 0.3.2-beta

- **Fixed: 1.7.10 mods in the `mods` folder did nothing.** Launchers (CurseForge, the Minecraft
  Launcher) install mods into `mods/`, where Forge 1.20.1 silently skips 1.7.10 mods and Retrofit
  didn't look: the game started with none of them. Retrofit now loads 1.7.10 mods from `mods/` too
  (linked into `retrofit/mods`, where 1.7.10 and its mods' dependency checks look), and
  `retrofit/mods` keeps working.
- Mods for other old Minecraft versions (1.12.2, 1.8.9...: their `mcmod.info` says) are not loaded,
  and the title screen says which; with no 1.7.10 mods at all, it says where they go.

## 0.3.1-beta

- **Downloads only with permission.** When the Minecraft launcher doesn't have Minecraft 1.7.10
  and Forge 10.13.4.1614, Retrofit no longer downloads them on its own: the title screen lists
  what it would download, from where (Mojang, Forge's Maven, Maven Central) and how much (about
  50 MB), and asks: *Download*, *Not now* or *Don't ask again*. 1.7.10 mods load after a restart.
  When everything is already there, nothing is asked. Dedicated servers stop with instructions
  until `download-files=yes` is set in `retrofit/retrofit.properties` (created on first start),
  like `eula.txt`; `never` there turns downloading and asking off.
- **Mods that download mods.** 1.7.10 mods with CodeChicken's DepLoader (CodeChickenCore,
  Thaumcraft, NotEnoughItems) download missing dependencies while the game starts, without
  asking. Retrofit finds those beforehand and lists them in the same question; 1.7.10 code may no
  longer download mods itself, and a start that fails for a missing one says which file to get.
- `-Dretrofit.minecraftHome=<dir>` for launchers that keep `.minecraft` elsewhere.
- Retrofit's downloads identify themselves (User-Agent "Retrofit (Minecraft mod)"); Retrofit
  collects no data.

## 0.3.0-beta

1.7.10 and 1.20.1 mods now work together, as if they were made for the same version:

- **Energy**: legacy machines speak RF (CoFH: BuildCraft, Thermal Expansion, EnderIO...) and IC2's
  EU; 1.20.1 machines speak Forge Energy. Legacy machines offer Forge Energy to 1.20.1 cables and
  machines (RF 1:1, 1 EU = 4 FE), and 1.20.1 energy blocks are RF handlers to legacy machines and
  join IC2's energy net as a sink and a low-voltage source.
- **Items**: 1.20.1 items with no 1.7.10 form travel through legacy inventories, pipes,
  machines and recipes as themselves (carried by `retrofit:modern_item`, numbered in
  `retrofit/modern-items.txt`). The Ore Dictionary and 1.20.1 tags are joined both ways
  (`ingotCopper` <-> `#forge:ingots/copper`, `logWood` <-> `#minecraft:logs`...).
- **Fluids**: every legacy fluid is a 1.20.1 fluid (with its own look) and every 1.20.1 fluid a
  legacy one. Legacy tanks have Forge's fluid handler; 1.20.1 tanks are IFluidHandlers to legacy
  pipes and pumps.
- **Creatures**: legacy mobs see 1.20.1 creatures, items and experience orbs (as their 1.7.10
  classes: a zombie is an `EntityZombie`, a modded monster an `EntityMob`), target and hurt them,
  push them, set them on fire, give them potions; magnets and vacuum hoppers pick up 1.20.1
  items. 1.20.1 AI treats legacy monsters as enemies (iron golems, guards), and Smite and Bane of
  Arthropods know undead and arthropod legacy mobs.
- **Blocks**: 1.20.1 mods' blocks are no longer plain stone to legacy code: quarries and miners
  get their real drops, hardness and light come from the block, and legacy ore generators
  replace modded stone.
- **Recipes**: legacy shaped and shapeless recipes (vanilla and Ore Dictionary ones) show in the
  recipe book, JEI, REI and EMI; crafting itself still goes through the legacy recipe list.
- **Creative tabs**: each mod's 1.7.10 creative tab is a 1.20.1 tab with the mod's own order,
  subtypes and prebuilt items; legacy items in vanilla tabs go to the matching 1.20.1 tabs.
- **Enchantments**: mods' enchantments are 1.20.1 enchantments (tooltips, enchanting table,
  anvils, books), and enchantments on legacy gear convert both ways.
- **Achievements**: mods' achievements are advancements, a tab per achievement page.
- **Commands**: tab completion for legacy commands.
- **Off hand**: legacy items work in the off hand (except ones used over time, like bows).
- Mobs that 1.20.1 spawns (natural spawning, spawn eggs, /summon) get their 1.7.10 spawn setup
  (`onSpawnWithEgg`: equipment, variants, sizes); held items and armour of legacy mobs show on
  clients.
- GregTech 5 Unofficial joins the tested mods. Fixed for it, and any mod alike: threads mods
  start no longer keep a stopped server running (they are daemon threads, as 1.7.10's exit made
  them in effect); `Arrays.asList(...).toArray()` keeps its array type as on Java 8 (GregTech's
  import of IC2's recipes); a deadlock when world generation placed tile entities in chunks
  that already had them; block items with more than 16 subtypes (GregTech's machines) kept only
  the first 16; `WorldServer.getPlayerManager()` knew no players, so packets mods send to a
  chunk's watchers (GregTech's machine data) never left.
- Vanilla GUIs legacy code opens (furnace, dispenser, hopper, brewing stand, beacon, crafting
  table, enchanting table, anvil) work, and mods' chat messages through `Minecraft.ingameGUI`.
- **Full world height**: legacy ore generators also fill the overworld below y 0 (a second,
  shifted pass that only keeps ores, in stone and deepslate; `-Dretrofit.deepOres=false` turns
  it off). Legacy mobs path find, and legacy code sees loaded blocks, below y 0, and mob spawners
  1.20.1 places are `TileEntityMobSpawner`s to legacy code.
- **Wood types for 1.20.1 mods**: each legacy wood (a log its mod crafts into planks) is also
  `<mod>:<wood>_planks` and `<mod>:<wood>_log`, named from its English name, so mods that add
  things for every wood type (Every Compat, Moonlight) find them. Those are names only: placing
  one places the legacy block. Ore Dictionary names whose blocks all belong become block tags
  too (`#forge:ores/copper`, `#minecraft:logs`).
- **Performance**: tile entities that never update (`canUpdate()` false, like GregTech's ore
  blocks) are no longer ticked, and legacy reads of the world are cheaper. In a heavy test pack
  (IC2, GregTech, BuildCraft, Thaumcraft, Witchery, with Mekanism and JEI) the server tick with
  574 legacy machines placed went from 37 to 10 ms (8 ms without any legacy blocks), and from 43
  to 16 ms with about 400 legacy creatures around as well.
- **Rendering**: blocks drawn by mods' renderers are lit like 1.20.1 blocks (their sides were
  dark); items with custom renderers and blocks with custom models are drawn in inventories and
  hands where 1.7.10 drew them (they were too big, misplaced or missing), including renderers
  that only handle some views (OreSpawn's Royal Guardian Sword and other big weapons in hand);
  tools and weapons are held like tools. Colours: items' render passes and colours
  (`getColorFromItemStack`: Thaumcraft's phials and wisps, spawn eggs, potions) and blocks'
  colours (`colorMultiplier`: leaves and grass in their biome's colour) were missing. Text of
  mods that reload their translations themselves (IC2's GUIs) is no longer raw keys.
- **Lines and block highlights**: lines legacy code draws (outlines, selection boxes, area
  previews) show, and `DrawBlockHighlightEvent` fires for the block the player looks at, so mods'
  own outlines replace the vanilla one.
- **Skies**: mods' dimensions with their own sky renderer (`WorldProvider.getSkyRenderer`) show
  that sky instead of the overworld's.
- **Leashes**: leads work on legacy creatures as in 1.7.10 (the creature follows, lets go
  beyond 10 blocks, drops the lead), and show on clients.
- Fixed: legacy render tick handlers (Thaumcraft's research popups) drew with the Tessellator
  that chunk building uses at the same time ("Already tesselating!"); one failing part of the
  legacy data pack no longer takes the rest (recipes, dimensions) with it.
- Fixed: potions legacy code throws (Witchery's coven witches) failed to convert or arrived as
  water; they arrive as the 1.20.1 splash potion of the same effect.
- **Villager trades**: mods' trade handlers (`VillagerRegistry`) give 1.20.1 villagers their
  trades, by the profession each grew out of; trades for mods' own professions go to wandering
  traders.
- **Player rendering events**: `RenderPlayerEvent` (Pre, Specials, Post) fires around the local
  player's model, so mods' capes, wings, goggles and transformations show, and cancelling or
  moving the player works.

- Mods' dimensions become 1.20.1 dimensions (`retrofit:legacy_dim_N`) generated by the mods' own
  chunk generators and populators, with their creature lists and their providers' fog and sky
  colours. Travel works through `travelToDimension` and the mods' Teleporters (Witchery's Spirit
  World, Torment and Mirror, Thaumcraft's Outer Lands, Mo' Creatures' Wyvern Lair).
- Players' legacy state (extended properties, Forge entity data, mod potions) survives dimension
  changes and logouts.
- Potion effects in both directions; mod potions' attribute modifiers (speed, health, damage)
  apply to the player. Flight that legacy code grants or takes away applies too.
- Riding: legacy mounts (brooms, horses, air sleds, dolphins) carry the player, steered with the
  player's movement keys; legacy creatures riding legacy creatures ride in 1.20.1 as well.
- Living events for players (attack, hurt, death, fall, jump, heal, break speed,
  `LivingUpdateEvent`); `EntityJoinWorldEvent` for legacy entities, and cancelling it works.
- Tooltips from `addInformation` and `ItemTooltipEvent`; `Minecraft.objectMouseOver`;
  `Minecraft.renderViewEntity` is the player outside world rendering.
- Block touches (`onEntityCollidedWithBlock`, `onEntityWalking`, `onFallenUpon`).
- Chunk loading: chunks forced by mods' `ForgeChunkManager` tickets stay loaded in 1.20.1.
- Vanilla chests, furnaces, hoppers, dispensers, droppers, brewing stands and any block with an
  item handler are inventories to legacy code (BuildCraft pipes, IC2 machines), as the matching
  1.7.10 tile entity classes.
- Fixed: legacy tile entities got `onChunkUnload` only as an invalidation (Ars Magica 2 power
  networks lost their nodes); `getEntityByID` on servers missed legacy entities (Thaumcraft's
  Traveling Trunk); mod blocks in legacy dimensions' generated terrain; container contents with
  more slots than the client's copy of a mod container.
- The mod sweep right-clicks and rides creatures, uses every item, travels to each dimension and
  checks host inventories.

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
