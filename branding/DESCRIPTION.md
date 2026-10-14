# Retrofit

**Play 1.7.10 Forge mods in Minecraft 1.20.1.**

Retrofit runs a real Minecraft 1.7.10 with Forge 10.13.4 inside your 1.20.1 game and connects it
to the 1.20.1 world. Your 1.7.10 mods run their own code, so their blocks, items, machines, GUIs,
creatures, recipes, world generation and sounds work as they were made, alongside your 1.20.1
mods.

> **Alpha.** A lot works; a lot is untested. Read the compatibility list below before building a
> world around it, and keep backups.

## Getting started

1. Install **Forge 1.20.1** (47.x) and put Retrofit in your `mods` folder.
2. Start the game once. Retrofit creates a `retrofit/mods` folder in your game directory.
3. Put your **1.7.10 Forge mod jars** in `retrofit/mods` and start again.

On first start Retrofit downloads Minecraft 1.7.10 and Forge 10.13.4.1614 from Mojang and Forge
(skipped if your launcher already has them). Retrofit itself contains no Mojang code and no mods.
Every start spends about 5-10 seconds booting 1.7.10 while mods load.

**Servers** work the same way: install it on a Forge 1.20.1 server and put the 1.7.10 mods in the
server's `retrofit/mods`. Players need the same 1.7.10 mods.

## What works

- **Blocks**: textures, custom models and tile entity renderers, collision, tools and tiers,
  redstone, right-click, placing, breaking and the mods' own drops
- **Machines and storage**: ticking tile entities, saving, client sync, and 1.20.1 hoppers and
  pipes moving items in and out
- **Items, tools, weapons, food and armor**, with the mods' own behaviour
- **GUIs**: the mods' own screens and containers, buttons, progress bars, shift-click
- **Creatures**: the mods' mobs with their own AI and models, spawn eggs and natural spawning
- **Recipes**: all crafting recipes, furnace recipes, fuels
- **World generation**: mod ores, plants and structures in new chunks
- **Commands, key bindings, HUD overlays, sounds, mod packets**
- **Multiplayer**: dedicated servers (LAN not tested yet)

Tested with BiblioCraft and Retrofit's own test mod. Big mods like Thaumcraft, Witchery, Ars
Magica 2, Mo' Creatures, OreSpawn, RotaryCraft and Carpenter's Blocks have **not been tested
yet**; reports are very welcome.

## Not yet

- Riding legacy creatures, leashes
- Some Forge events (living entity events, tooltips, player rendering)
- Legacy crafting recipes in the recipe book and JEI (they still craft)
- 1.7.10 code expects the world to start at y=0: creatures below y=0 can't find paths

## Known issues

- Connecting to a server right after the game starts can occasionally hang at "Logging in...".
  Cancel and connect again.

## Requirements

Forge 1.20.1 (47.x), Java 17. Internet on first start, unless the Minecraft launcher already has
1.7.10 and Forge 10.13.4.1614 installed.

## Credits

Built on Minecraft Forge and FML for both versions, and on everyone who wrote the 1.7.10 mods
this is for.
