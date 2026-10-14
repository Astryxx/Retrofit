# Changelog

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
