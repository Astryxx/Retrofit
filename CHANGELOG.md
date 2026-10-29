# Changelog

## 0.6.1-beta (in progress)

- Stabilized the Mob Battle group-item client test: it now starts with a fresh nearby pair. Reusing
  fighters from the preceding battle sometimes put them beyond the golem's follow range, where
  1.7.10's target AI correctly clears the target. The mobs' gameplay rules remain unchanged.

- Integrated UsagiTsukino-01's entity fixes: direct blast motion reaches modern creatures without double
  knockback; entity tracking follows the mod's registered range; client copies use the server's health;
  direct host health increases reach legacy creatures as heals. Existing Mob Battle targeting and
  multiplayer registry fixes are retained.
- Fixed the Ruby Golem test creature's zombie skin layout: its generic biped model sampled the wrong
  texture regions, leaving body parts invisible during Mob Battle tests.

- **1.20.1 creatures hit 1.7.10 ones as themselves**: a zombie's hit on a 1.7.10 mob reached it as damage from
  nobody, which 1.7.10 treats like the generic kind that ignores armor, and mobs' own rules for who hits them
  (OreSpawn's Mobzilla: its 21 armor and its damage cap) didn't apply. The attacker now arrives as the 1.7.10
  stand-in legacy code already sees for it (shot arrows and tridents as projectiles from it), so armor and those
  rules apply as they do for players and 1.7.10 mobs, and the mob turns on the 1.20.1 creature that hit it.
  Damage from things legacy code can't see no longer ignores armor either. The Titans' titans, which refuse
  attacker-less damage and take a boss's hits x100, fight 1.20.1 creatures (a 1.20.1 wither arrives as a 1.7.10
  EntityWither), and stand-ins of 1.20.1 mega bosses stay narrower than the titan-sized attackers titans refuse.
  (Contributed by UsagiTsukino-01, with Retrofit's own.)
- **Damage by health write reaches 1.7.10 mobs** (OmniMobs' Flashfur): mods that "force" damage by writing the
  target's health (never calling hurt) had it undone by the next sync. A drop on the host is now delivered to
  the 1.7.10 entity, named after the creature that last hurt it; OmniMobs' no-damage watchdog, which erased
  targets that took no damage, no longer erases 1.7.10 mobs that refuse it by their own rules.
  (Contributed by UsagiTsukino-01.)
- **Boss bars come back after a mob leaves and re-enters sight** (The Titans): a re-created client copy shares
  its server id with the dead one, which 1.7.10's Entity.equals compares, so it never rejoined the world's
  entity list; membership is by identity now, dead copies leave the list, a failed copy is retried instead of
  blanking the mob for good, and one bad tick no longer removes a mob. Mod entities keep the tracking range
  their mod registered (The Titans: 128 blocks), and the client player's ticksExisted and timeUntilPortal
  count (The Titans' boss bar scan, portals). (Contributed by UsagiTsukino-01.)
- **Targets 1.20.1 code sets reach 1.7.10 mobs** (Mob Battle's enrager and other items or mods that pick a mob's
  target): the 1.7.10 AI gets it, as its attack target, revenge target and its older AI's target. Target goals
  1.20.1 mods add to 1.7.10 mobs run (Mob Battle's armies: a 1.7.10 mob in a team turns on the other teams), and
  targets 1.7.10 code sets on 1.20.1 creatures are theirs. Healing from 1.20.1 code (Mob Battle's heal item,
  instant health) heals 1.7.10 mobs.
- **Left and right clicks on creatures reach 1.7.10 code**: a player's attack fires 1.7.10's AttackEntityEvent
  and the held item's onLeftClickEntity, either of which can stop it (Mob Battle's sticks pick mobs instead of
  hitting them), and a hit that lands is the item's hitEntity (weapons that poison, burn or wear on hits). A
  right click on a 1.7.10 creature does all of 1.7.10's interactWith: EntityInteractEvent, the creature's own
  interaction, then the held item's itemInteractionForEntity; on a 1.20.1 creature the event and the item's
  use. Both Mob Battle versions (1.7.10's and 1.20.1's) work on 1.7.10 and 1.20.1 mobs alike.
- **Machines are on the world's tile entity list** (loadedTileEntityList), where 1.7.10 mods look for them: Rival
  Rebels' tokamak finds and powers its teslas, and Rhodes finds tokamaks. A chunk's 1.7.10 tile entities are
  made when it loads, as 1.7.10 did, so the ones nothing has touched yet are listed too; they leave the list when
  broken or unloaded. (Contributed by UsagiTsukino-01.)
- **1.20.1 armor, swords and tools are what they are to 1.7.10 code**: they travelled as one plain item, so
  1.7.10 creatures that put on armor or take up weapons (Better Dungeons' NPCs) refused netherite and other
  mods' armor, and code asking what a tool is saw none. Armor is now an ItemArmor for its slot, protecting
  by the piece's own armor points and drawn with its own texture; swords hit with their own damage; pickaxes,
  axes and shovels (any mod's, by what they can do) are tools of their kinds, harvest level and speed. They
  turn back into the same 1.20.1 items.
- **Mods' models drawn from vertex buffers show** (NTM: Space's bombs, machines and most of its items, which
  were shredded geometry in the world and spilled out of inventory slots): buffer objects (GL15) are kept, and
  the arrays pointed into them are read when drawn.
- **Breaking a block as a player lets it do what it did after being broken** (onBlockDestroyedByPlayer):
  OreSpawn's Red Ant and Termite ores let their creatures out instead of only dropping.
- **Spawn eggs for mobs whose mod adds its egg by hand** (an egg under an id that leads to the class, without
  the class leading back to it).
- **Two mods may ask for the same global entity id**: the second one's creature gets a free id past 255 (with its
  spawn egg) instead of failing the mod (MLP Mythical Creatures' 226 to 241, which The Titans has).
- **A mod's world generator that throws no longer stops the others**: FML ran them one after another, so every
  generator after a failing one did nothing in that chunk. They now run one at a time (with FML's seeds), and a
  failing one is logged once with its stack trace. One such failure was Retrofit's own: a mod biome without a name
  broke the biome lookups legacy generators make (Ars Magica, OreSpawn, Thaumcraft, GregTech, CoFH's ores and
  others generated nothing near it) and the creature lists of mod dimensions (nothing spawned by itself in Erebus
  or OreSpawn's Utopia).
- **Mod dimensions spawn their own creatures** (AbyssalCraft, Atum, Erebus, the Betweenlands; players saw none):
  the creatures a dimension lists come from its biome there, which outside chunk generation was looked up as
  plains (so only mods that add their creatures to every biome spawned). And 1.7.10's server told every world
  what it may spawn (hostiles unless peaceful, animals), which Forge passes to its provider: dimension mods keep
  that there for their own spawners (Erebus'), which spawned nothing without it.
- **Dimension mods find their own generator** (theChunkProviderServer.currentChunkProvider, where 1.7.10 kept
  it): the Betweenlands' decorators cast it to theirs, failed in every chunk, and its plants never grew.
- **A library a mod can't get no longer stops every mod**: mods whose DepLoader downloads a library
  (ForgeMultipart's CodeChickenLib, whose 1.1.3.127 address is gone) failed the whole 1.7.10 start when the
  download failed or the player said no. Such a mod now waits in retrofit/waiting-for-libraries, listed as not
  loaded with the file it needs; the others load, and it comes back by itself once the file is in retrofit/mods.
- **Two versions of one mod no longer stop every mod**: FML found "a duplicate mod" (two packs' CodeChickenCore,
  1.0.7 and 1.4.22) and loaded none. The newest is kept and the others are moved to retrofit/duplicates (moved,
  not deleted) and listed as not loaded.
- **Each mod sees its biome ids also while worlds generate**: a mod whose biomes moved off ids another mod had
  looked them up by its config's ids in its generator and got the other mod's (Erebus, beside AbyssalCraft and
  ExtraPlanets: no terrain). In its own classes, a mod's biomes are at the ids it asked for, at any time.
- **Generation no longer hangs on columns it can't see**: around a generating chunk only its neighbours are
  there; Retrofit said the rest was too, as all air, and ExtraPlanets' tower placement searched one for ground
  forever, holding up the server. Such columns aren't there now, and have no top (-1), as on 1.7.10.
- **Mods' spawners pick heights in the right range** (the Betweenlands' creatures spawn): a chunk's highest
  section (getTopFilledSegment) was always 0.
- **Java 8's UUID.fromString** for legacy code (More Planets' potions failed on Java 17's stricter one).
- **Placing Nevermine's Haven portal no longer crashes the server**: its shape code sets its own metadata, which
  asked its shape again without end. A block's shape asked while it works out its shape gets its metadata's;
  and Retrofit's error reporting can no longer itself fail (it did, first used deep in that overflowing stack,
  and then every later error crashed the game).
- **A mod whose proxy class name differs from its class only in case** (Arcana RPG's "commonProxy" for
  CommonProxy, which worked on the case-insensitive disk it was made on) loads: the class is found in any case.
- **A server and a client with different 1.7.10 mods are told which**: the player saw Forge's "mismatched mod
  list" with no word on why. The 1.7.10 mods are now compared once the player joins, by each mod's own rule, as
  FML 1.7.10 did: a client may have mods the server hasn't (Mouse Tweaks, minimaps), and a server's mods are
  needed on its clients at versions they accept. A player turned away sees the mods that differ, and that a mod
  the server couldn't load (its log names it) has to be removed from the clients too. Another Retrofit version
  on the server is told as such.
- **Java 8's array decoders**: mods that asked whether a charset decoder is one (sun.nio.cs.ArrayDecoder, which
  Java 17 doesn't export) failed; Minecraft Animated's model loader stopped Monster Hunter Frontier. They now
  take their other way of decoding.
- **1.7.10's rules for when an entity is drawn**: its own render distance (isInRangeToRender3d, with the mod's
  renderDistanceWeight) and ignoreFrustumCheck, which models far bigger than their hitbox set so they don't
  vanish while their hitbox is off screen.
- **ManaMetal's client side runs again** (its HUD, class level bar, money, particles the server asks for):
  1.7.10's client had no network proxy set, so a class ManaMetal builds its player messages with failed to
  load, and with it the rest of ManaMetal's client tick, every tick.
- **Mods' player data reaches the players** (ManaMetal's mana, thirst, class and death screen; any mod sending
  "player N" to clients): the 1.7.10 player on the server and on its client had different entity ids, counted
  apart on each side, so data sent for the server's player found nobody on the client. Players' 1.7.10 ids
  now come from their 1.20.1 id, the same on both sides.
- **Mods that send what they keep in chunks to the players watching them work** (Forge Multipart's parts, which
  no player saw; CodeChickenLib's world extensions): players starting and stopping to watch a chunk fire
  1.7.10's ChunkWatchEvent; a chunk's tile entities are listed in its chunkTileEntityMap; the chunk object
  chunk events carry is the one the world hands out (they were two views of one chunk), and a chunk is saved
  as the one it unloaded as, which 1.7.10 did in that order.
- **Mods' item renderers get the light the item is in** (1.7.10 set it before drawing a held item, and
  CodeChickenLib's renderers read it back): Forge Multipart's parts and saws were black in the hand.
- **1.7.10 armor that never wears doesn't break at the first hit** (ManaMetal's lasting armor): a piece with no
  durability, which 1.7.10 never damages, was given 1.
- **Forge Multipart loads**: mods asking for one library at different versions get only the newest, as
  DepLoader merged them (CodeChickenLib 1.1.3.141 for CodeChickenCore, which can still be downloaded, instead of
  also Forge Multipart's 1.1.3.127, which can't, and set Forge Multipart aside); a library older than the one a mod
  asks for (CodeChickenLib 1.1.3.140 for CodeChickenCore's 1.1.3.141) was deleted by CodeChicken's DepLoader,
  which then tried to download the asked one and stopped every 1.7.10 mod; the one there is used now. And ASM's
  Type.getReturnType behaves as in the ASM 1.7.10 shipped (Forge Multipart reads field types with it).
- **1.7.10 swords, tools and armor get 1.20.1's attributes** (asked for by players): swords attack 1.6 times a
  second, pickaxes 1.2, axes and shovels 1.0, as 1.20.1's do; armor gets toughness from its set's armor points
  (a diamond-like set 2 per piece, iron and below none). Players set them per item, or turn them off for some
  or all items, in retrofit/item-attributes.properties.
- **1.7.10's own texts are translated** (chat.type.achievement showed when a player earned an achievement):
  1.7.10's English is used for the keys 1.20.1 hasn't, after the mods' own.
- **A creature whose renderer fails once is still drawn**: one failed frame hid its whole kind for the rest of
  the session; now only many failures in a row do.
- **A mod that fails on the server only no longer keeps players out** ("Missing registry data"): what a mod
  left out had registered (Arcana RPG's effects and enchantments, on dedicated servers, where it fails from
  client-only code) is kept out of 1.20.1's registries, which joining players must match.
- **Players joining a server take its 1.7.10 block and item ids** even where theirs differ: the map was applied as
  a world save's, which makes FML back up the world first, and a client has none to back up, so the ids were
  never taken.

## 0.6.0-beta

- **A page for the 1.7.10 mods** (Options, "1.7.10 Mods..."), laid out as Forge's mod list: every 1.7.10 mod
  with what its mcmod.info says and its logo, the ones that didn't load in red with the reason, and a Config
  button: the mod's own configuration screen, or FML's for its .cfg files (saved when it closes; most 1.7.10
  mods read them only at startup, which the page reminds you of). Retrofit's own settings are a button away.
- **Mods' own particles are drawn** (custom EntityFX classes: ManaMetal's scythe, gun and wand projectiles,
  which are nothing but particles): they are kept, updated each tick and drawn each frame as 1.7.10 did, also
  when a mod puts its own effect renderer in place (DragonAPI's). Before, only particles with a vanilla
  counterpart showed.
- **Legacy code culls by the real view** (ClippingHelper, Frustrum): the camera's planes each frame, and a
  Frustrum at the camera as 1.7.10's renderWorld made one. Everything tested against them was out of view
  (with DragonAPI, every mod's particles).
- **Mods' ores generate below y 0** (in the deepslate layer): 1.7.10's ore generators replace stone, and saw
  deepslate as a block they leave alone, so the deep pass of 0.5.0 placed none in real worlds. Deepslate, tuff
  and the other rock ores go into read as stone while legacy generators run.
- **Mods that draw tooltips themselves** (a coremod replacing GuiScreen.renderToolTip, as ManaMetal's does) draw
  them, in their own screens and in 1.20.1's, with their frames, separators and icons; before, Retrofit's own
  tooltip hook replaced theirs and their markers showed as text (`[M3LINE]`).
- **Icons mods put in text** through font pages of the private use area (`unicode_page_e0.png` and on: ManaMetal's
  tooltip icons) are drawn; they were empty boxes.
- **The HUD events are 1.7.10's**: every element GuiIngameForge fired (helmet, portal, hotbar, crosshairs, boss
  health, health, armor, food, mount health, air, jump bar or experience, and the text lists), in its order.
  Thaumcraft's research notifications and the aspects flying to the corner as you scan come at the text element,
  which wasn't fired. What mods draw before the hotbar goes under it (ManaMetal's food decay bars covered the
  items), and an element a mod cancels leaves out 1.20.1's own (a mod's hotbar or health bar replaces it).
- **Thaumcraft's thaumometer sits where it did** (it was drawn too high): first-person renderers that place
  themselves by the held item's equip progress get it at rest.
- **Writes into a chunk's block storage reach the world** (ManaMetal builds its instance dungeons there, for
  speed; OreSpawn's setBlockFast builds its houses, castles, mazes and islands the same way): only the
  structures' blocks with tile entities appeared, as scattered pillars, spawners and chests.
- **Big generated structures are built in full** (OreSpawn's Enormous Castle, and any mod's structure reaching
  past the chunk next door): 1.20.1's feature stage only takes writes to the generating chunk and its
  neighbours, where 1.7.10 loaded whatever chunk a generator wrote to. The rest are queued for their own chunk
  and placed when it generates, or on the server thread for a chunk that finished first, with the spawner's
  mob and the chest's loot the generator gave them. The queue is saved with the world, so a restart before
  those chunks generate loses nothing.
- **OptiFine for 1.7.10 is left out instead of stopping every 1.7.10 mod**: it patches 1.7.10's renderer, which
  1.20.1 replaces, and the patched code failed before any mod loaded. It is listed with the other mods that
  didn't load, with why.
- **Players can join servers with large packs** (Thaumcraft, Railcraft, The Titans and the like together):
  the client and server could register different potion effects or entities, and joining failed with
  "Missing registry data" (Forge then crashed drawing that message). A potion asking for a taken id is now
  moved above 127 (up to 255, as DragonAPI allows) instead of into the free ids below: mods that look for a
  free id themselves look there, and Thaumcraft left out its Flux Taint when the moved potions had filled
  it, on one side and not the other. Every potion a mod makes is registered, also one that lost its slot.
  And a mod that takes over a vanilla entity name (Railcraft's minecarts) left two classes under that name,
  of which either side could keep either: the mod's is kept.
- **An invulnerable player takes no damage from 1.7.10 mobs** (the invulnerable ability, which commands and
  mods give, not only creative mode): 1.7.10 code saw it only in creative.
- **"Mismatched mod list" can be traced**: each side's log names the 1.7.10 mods (and versions) its network
  version is made from, so two logs show which mod differs.
- **Iron bars, fences and panes of generated structures join up** (the Enormous Castle's walls were scattered
  posts): 1.20.1 keeps connections in the block state, so once the generators are done every block they wrote
  takes the shape its neighbours ask for, as vanilla's structure templates do.
  (Both contributed by UsagiTsukino-01.)
- **Creatures spawn by themselves in mods' dimensions again** (none did, in any: Erebus, Betweenlands, OreSpawn's):
  the creatures their generators list are handed to 1.20.1's spawner as the same entries each time, as a biome's
  are; it asks twice and spawned nothing when the second answer was a new list.
- **OreSpawn's Crystal dimension runs at full frame rate** (55 fps there against 180 elsewhere in the test): its
  stone and grass are opaque cubes only while the player is in that dimension, which 1.20.1's fixed block
  properties can't follow, so every buried face was drawn (116 million indices in view). Faces against a legacy
  neighbour are now left out where 1.7.10 left them out: behind a block that says it is an opaque cube now
  (asked each tick), or whose face covers them with an opaque texture.
- **Entity shadows are round again** (OreSpawn's ants and most mobs drew a grid of shadows): legacy textures keep
  their `.mcmeta` clamping and blur, which 1.7.10's shadow texture needs.
- **Two mods can use the same dimension id** (Atum and Advent of Ascension both default to 17, and many dimension
  mods share ids): the mod registering a taken id (or provider type) gets a free one from 1000 up, the same one
  at every start (`retrofit/dimension-ids.txt`), and still sees the id it asked for everywhere in its own code:
  its config's number, its portals' checks, its teleports, its packets and events. The rest of the game sees the
  real ids. Before, the second mod had no dimension and its portal led into the other mod's.
- **A dedicated server's 1.7.10 configs reach its players**: joining with different configs (`retrofit/config`,
  and the dimension ids above) asks the player whether to use the server's; Retrofit then saves them (keeping
  the player's own in `retrofit/config-backup`) and asks for a restart, as 1.7.10 mods read their configs only
  at startup. Files named `*client*` are each player's own and never sent.
- Legacy potion effects and enchantments are named the same whatever ids their configs give them (two with
  one name are numbered in a fixed order; one without a name is named by its class), so clients and servers
  with different numbering still connect.
- **More textures**: icons mods cut from a sheet of their own (Chisel's connected-texture pillars and panels:
  79 in the test pack) come from that part of the sheet; files whose name differs only in case, or that sit
  under another asset folder of the same jar (BuildCraft's triggers), are found. The rest of the textures
  legacy icons name but no jar has are listed in `retrofit/missing-textures.txt` (most are missing on 1.7.10
  too).
- **Dyed armor shows its colour**: leather-material legacy armor and armor with its own colours are tinted, and
  dyeable as leather is (in crafting and cauldrons).
- **Items used over time work in the off hand** (bows, food, wands), as in the main hand.
- **RenderPlayerEvent fires for other players too** (capes, wings, mods' models of other players), with each
  player's own legacy copy (EntityOtherPlayerMP, as 1.7.10 had them).
- **Shapes that depend on neighbours** (fences, panes, cables, covers) collide and outline as they connect: the
  block is asked where it is, as 1.7.10 asked it.
- **IC2's energy net gets as much energy from 1.20.1 blocks as they give**, still at low voltage (no machine
  explodes): it was 32 EU/t per block.
- **Mods' own clouds and weather** (WorldProvider.getCloudRenderer, getWeatherRenderer) are drawn in their
  dimensions.
- **Triangle strips, fans and polygons drawn through vertex arrays** (the Tessellator in those modes: discs,
  cones, beams) are drawn; only immediate mode drew them before.
- **Recipe viewers list more legacy recipes**: subclasses of the vanilla and Ore Dictionary recipe classes, and
  mods' own recipe classes whose inputs their fields show (only when the recipe itself accepts that reading).
- HBM's planets (and other mod dimensions whose provider makes its biomes when a world is made for them) load:
  the biome is the mod's, checked against its own view and moved off another mod's id, and made again into
  its own slot.
- The legacy lightmap (EntityRenderer.lightmapColors) is 1.20.1's, every frame (HBM's skies read it); NEI's
  per-server settings get the server's address.
- Mod dimensions generate blocks with ids past 4095 (Erebus: one of its chunk's blocks failed the chunk).
- Faster loading: the scan for the names each class transformation looks for is one pass per class.

## 0.5.2-beta

- **Erebus generates its dimension again** (reported with Erebus and HBM together): a
  mod's view of the biome array, which lets mods sharing biome ids load (0.5.1), was also shown while chunks
  generated, to whichever mod's code was running (FML guesses the "active" mod from the call stack). HBM's
  generator asked Erebus's biome layers for a biome and they got HBM's view, without Erebus's biomes: nothing
  generated past the error. Views are now shown only while FML sends a mod its loading events.
- **Chests and spawners legacy generators set up keep what they put there**: a vanilla chest a mod's
  structure fills, or a spawner it names a mob for, came out empty and spawning pigs (OreSpawn's
  structures, and any mod's dungeons). They get a 1.7.10 tile entity while their chunk generates, and its
  inventory (each stack converted, mods' items too, for chests, furnaces, dispensers and the like) or its
  mob and spawner settings go into the 1.20.1 block entity when the chunk is done.
- A mob legacy code names for a spawner in a loaded world now reaches the 1.20.1 spawner too (before, only
  the stand-in legacy code saw changed).
- **Mods' teleporters find the ground**: they looked for it before the chunks there were loaded and saw only
  air (OreSpawn's dimensions put players at the bottom of the world). The chunks around where the player
  arrives are loaded first, as 1.7.10 loaded them when anything looked.
- **Mod portals no longer send players straight back** (Atum, Advent of Ascension: the screen flickered
  between "joining world" and "loading terrain"): the portal cooldown (timeUntilPortal) the mod set before
  sending the player stayed with the player's legacy copy in the old dimension, and the player arrived in
  the return portal with none. It now follows the player, and counts down every tick as 1.7.10 counted it.
- **One block can no longer break every 1.7.10 mod's textures**: all legacy models and textures are made into
  one resource pack, and a single block that threw while it was made (one of Witchery's, after Witchery failed
  to load) left the whole pack out. Each block and item is now made on its own; one that fails looks like a
  missing model, and the log names it.
- **Block ids past 4095 work for fire and statistics**: 1.7.10's fire (flammability and spread) and its mining
  statistics kept tables of 4096 blocks, which 0.5.0's wider block ids overran. In a large pack (Erebus and
  Atum added to the test pack) Witchery's logs landed past them, and Witchery failed to load.
- **Mods whose biomes moved to free ids see them where they asked** while they load: each mod's view of the
  biome array was kept from before its own biomes were made (its free-id check), and a count shared by every
  mod's view let it stand. Erebus then found its biomes missing and was left out.
- **Mod dimensions answer biome questions with their own biomes**, as 1.7.10's dimensions asked their biome
  provider: legacy code got plains (Erebus's caves expect one of Erebus's biomes, and its caves failed).
- A player sent to a mod dimension without a teleporter stands on its ground: the height there was read before
  the chunk was loaded.

## 0.5.1-beta

- **Dimensions mods make while playing work** (Galacticraft's space stations, and any mod's pocket or
  personal dimensions made the same way): a dimension registered after the server started gets a
  1.20.1 level then, with the mod's generator, saved with the world. It comes back when the mod registers
  it again after a restart, and players who left the game there come back there.
- **A mod's own teleports take the player along**: mods that move a player to another dimension
  themselves (as Galacticraft does, rather than through transferPlayerToDimension) now move the real
  player too, to where the mod put them; before, the player stayed behind and fell.
- A server's dimensions share one world data store (`mapStorage`), as on 1.7.10: dimensions made while
  playing saw nothing the others kept there (Galacticraft: "bad home planet ID").
- Server worlds have the `theChunkProviderServer` mods reach for (Galacticraft loads the arrival chunk
  through it; it was null), its loaded-chunk map answering for the host's chunks (IC2 looks its
  machines' chunks up there).
- **Riders sit where the mount puts them**, also below its top (Galacticraft's rockets: inside, not on
  the nose), on clients too.
- **Entities are drawn where 1.7.10 drew them**, their `yOffset` above their feet: Galacticraft's
  rockets stand on the launch pad, not sunk into it.

- **Mod dimensions generate blocks past id 4095 as themselves**: the 1.7.10 chunks their generators
  fill keep the bits of a block id past 12 beside them (0.5.0 widened block ids, which those chunks
  could not hold).
- The legacy side compiles against Forge 1.7.10 as other developers have it (thanks to a fellow mod
  developer): it used an event constructor Forge only adds at run time. `checkAgainstCleanForge` checks.
- **Biome ids no longer clash**, as enchantment and potion ids no longer do: 1.7.10 let a biome taking another's id replace it (the other
  mod's world generation then made the wrong biome); it gets a free id below 256 instead (logged).
- **Mods that check their biome ids before taking them load together** (thanks to a fellow mod
  developer): Erebus and Advent of Ascension both default to biome 100, and Erebus refused to load
  ("can not use biome ID 100 because it's being used by ..."). While a mod loads, 1.7.10's biome array
  (getBiomeGenArray) shows it the vanilla biomes and its own, at the ids it asked for, so its checks
  pass (HBM's own check of its dimensions' biomes too, which left a dimension out); its biomes still get
  free ids in the real array, so each mod's world generation finds its own.
- **A dimension id another mod registered first is skipped** with a warning (thanks to a fellow mod
  developer): Forge's registerDimension threw, and 1.7.10 left the whole second mod out.
- **Textures mods make at run time show** (Extra Utilities' Tinkers' Construct parts of its materials,
  450 icons): a texture in no mod's jar is asked of 1.7.10's resource manager, with the resource packs
  mods add there.
- The Pistronics piston play test checks against real 1.7.10: its pistons move nothing in the test's
  layout there either, and Retrofit leaves the same blocks, 1.20.1's prismarine among them.
- **Block items and armor show their mods' own names** (RotaryCraft's and Extra Utilities' blocks,
  ManaMetal's armor), as items already did: their names were the keys (`tile.block.beam.name`, not
  "Beam Block"). A name a mod's code gives as a key 1.20.1 can translate is translated (IC2 keeps its
  names on the legacy side itself, and a resource reload took them out again: "ic2.blockAlloy.name").
  Checked against real 1.7.10's names for the same mods: 187 names differed, these.
- **Boss health bars and HUD drawing follow 1.7.10's order** (thanks to a fellow mod developer): the
  HUD's Pre events are fired too, BOSSHEALTH is among the elements (where The Titans draws its bosses'
  bars), a cancelled Pre skips its element's Post, and a cancelled Pre(ALL) skips the rest, as Forge
  1.7.10's GuiIngameForge did.
- **1.7.10's vanilla-style boss bar shows in 1.20.1's boss bar overlay** (thanks to a fellow mod
  developer): the bar bosses set through BossStatus (Thaumcraft's, Witchery's and others using
  vanilla's) is one entry there, named and filled as 1.7.10 drew it, and counts down as there, so it
  goes when the boss does. GuiIngameForge.renderBossHealth and a cancelled Pre(BOSSHEALTH) turn it off,
  as mods with their own bar (The Titans) do.
- **Legacy entities another mod removes leave with their carriers** (thanks to a fellow mod
  developer): entity removers and lag clearers take entities out of the level without remove(), and
  the client's removal path doesn't call it either. Each tick finds such carriers and takes their legacy
  entities out of the legacy world, dead, as 1.7.10 did: The Titans' boss bars let go of removed titans.
- **Watchable attributes reach client copies** (thanks to a fellow mod developer), as 1.7.10's entity
  tracker sent them: The Titans keep their bosses' health in an attribute of their own, so their bars
  filled up again on clients. The network protocol is 3; client and server builds must match.
- **The legacy profiler exists while mods load** (thanks to a fellow mod developer): The Titans keep
  Minecraft.mcProfiler from their boss bar's constructor, and it was null until the first frame.
- **Mods' HUD code doesn't run as in-game on the title screen** (thanks to a fellow mod developer):
  with no player, `currentScreen` was null, which mods read as "in game" before using `thePlayer`
  (Advent of Ascension failed every frame); a blank screen stands in, as 1.7.10's menus were screens.
  What tick and render-tick handlers draw where nothing reads it is cleared, so it no longer piles up
  until memory runs out (AoA's notice board on the title screen).
- **A mod left out late keeps its textures and names.** A mod that fails after registering its blocks
  and items (Thaumcraft, in postInit) keeps them, but its jar was left out of the asset pack with it:
  they drew as missing textures and showed their keys as names.

## 0.5.0-beta

- **Mod entities whose classes squat in net.minecraft.\* spawn.** Mods that put their classes
  outside their own package but under net.minecraft (The Titans: net.minecraft.theTitans.*) were
  taken for vanilla entities: no 1.20.1 entity type was built for them, and spawning one (its spawn
  eggs) failed with `ResourceLocationException: Non [a-z0-9/._-] character in path of location:
  minecraft:thetitans.WitherBossTitan` after DataFixerUpper logged `Unsupported key`. A class now
  counts as vanilla only if it is the class one of 1.7.10's own EntityList names (Zombie, Boat...)
  stands for (The Titans keeps its entities in net.minecraft.entity.titan.* too), so such mods'
  entities register and spawn like any other legacy entity, and their biome spawns count too.
  Entity data that reaches the host's vanilla conversion under a legacy name (spawners and other
  stored state keep them; 1.20.1 then logs `Skipping Entity with id`) is hosted as the legacy
  entity it names, loaded from its own NBT, and a name that names no 1.20.1 entity at all fails
  that one spawn with a clear log line instead of a stack trace or a silent skip.
- **Empty item tags stay empty.** A legacy item's tag that was empty became null on its way back to
  the mod, so mods that create the tag in `onUpdate` when it is null lost what they kept in it. A
  tag that held only Retrofit's own bookkeeping (subtype, damage) still becomes null, so stacking is
  unchanged.
- **The resource pack repository exists while mods load**, as Minecraft's constructor made it: mods
  that read it in preInit (Hbm's manual loader, from a reload listener) found it null, since the
  bridge created it only once the client started.
- **Mods that fail while loading are left out with mixins installed too.** A mixin (with UniMixins)
  rewrites FML's LoadController as newer bytecode, and Retrofit then left it unchanged, so one mod
  that threw in preInit stopped every 1.7.10 mod again.
- **Enchantment and potion ids no longer clash.** Mods choose these ids by hand, and two mods often
  chose the same one: 1.7.10 then refused the second enchantment (Advent of Ascension's and Ars
  Magica 2's, both 100: the mod failed to load) and let the second potion silently replace the
  first. They live in 1.20.1's registries by name, so a mod asking for a taken id now gets a free
  one (logged), and keeps its own whenever it is free. Potion ids may go past 1.7.10's 32.
- **Mods' potion effects are 1.20.1 effects by name** (Witchery's paralysis is `retrofit:paralysis`),
  as their enchantments already were, instead of by number (`retrofit:legacy_potion_32`): numbers
  change when mods are added or removed, and saved effects then pointed at another potion. Effects
  saved under the old numbered names are dropped once; an enchantment or potion sharing another's
  name gets `_2`, `_3`..., not its number.
- **No more running out of ids.** Global entity ids (254, sized for 1.7.10's spawn packets) and
  block ids (4096, sized for its chunks) are sized for packets and storage 1.20.1 replaces; large
  modpacks used them up and the next mod failed ("No more entity indicies left": The Titans;
  "maximum id range exceeded": Fisk's Superheroes after HBM's blocks). Entity ids now go up to
  32766 and block ids up to 31999, the lowest free one first as before, so packs that fit keep
  their ids.
- **Worlds no longer hang while loading when a mod forces chunks early.** Custom Mob Spawner makes
  one of every entity as a world loads, and HBM's missiles force their chunk from their
  constructor; Forge loaded that chunk on the spot, and the server waited for it forever. Forced
  chunks now load on the server's next tasks, as on 1.7.10.
- **Legacy blocks keep their textures with mods that pick GL texture units** (ManaMetal, before every
  Tessellator draw): OpenGlHelper's texture units were left 0, both read as the lightmap's, and the
  block texture's coordinates went there. Every legacy block and block item drew as one flat colour,
  or not at all in large packs.
- **Moving a 1.7.10 mod from `mods/` to `retrofit/mods/` no longer deletes it.** Retrofit links the
  1.7.10 mods of `mods/` into `retrofit/mods/` and removed a link once its mod left `mods/`, even when the
  file there was now the player's own copy, moved in. A file there that is no longer the one linked
  stays; one that still is moves to `retrofit/mods-removed/` (the log says so), never deleted.
- **Worlds load after a mod dimension goes away.** A world keeps the dimensions it was played with,
  and 1.20.1 refused to load it once one of them was no longer provided (its mod taken out, or a mod
  that numbers its dimensions by the next free id, which moves when mods change: "Failed to get
  element ResourceKey[minecraft:dimension_type / retrofit:legacy_dim_2595]"). Retrofit remembers the
  mod dimensions a game has had (`retrofit/legacy-dimensions.txt`, and those of worlds played before)
  and keeps their types, so the world loads, with that dimension empty, as 1.7.10 loaded it without.
- **Dedicated servers start with Pistronics** (and other mods whose blocks only know how they are drawn
  on clients): asked for its render type on a server, one such block stopped the server from starting.
- A class asked for again after it was found nowhere fails at once, without recording another stack
  trace: mods ask for an absent mod's class over and over (Thaumcraft, at every step of working out
  aspects through all recipes), and each miss cost as much as the stack was deep.
- Legacy rendering starts from a fresh RenderBlocks state for every block and item, as 1.7.10's chunk
  rebuilds did: a mod leaving an override texture, rotations or locked bounds behind no longer spoils
  what is drawn after it.
- **1.20.1-only blocks no longer read as air in small packs.** Retrofit's stand-ins for them (prismarine,
  deepslate, 1.20.1 mods' blocks) were only looked for past the 256 vanilla ids; with few mods installed
  FML numbers them below that (and mod blocks too, in mods' dimensions). Legacy code saw air there and
  built over them.
- **Hits on 1.20.1 creatures show at once**: legacy code reading a creature's health right after hurting
  it saw the health from before the hit until the end of the tick (Pistronics' petrify arrows never
  made statues of what they killed).
- **Creatures near a player who just teleported stay.** The player's legacy self followed only when
  the player ticked, after the world's creatures, so for one tick they measured the distance to where
  the player had been, and monsters there despawned as 1.7.10 despawns monsters far from every player.
- A 1.20.1 block that legacy code moves with its tile entity (as Pistronics' pistons move blocks) is
  put back as itself: the stand-in put down without it clears the place, and putting it down again
  over itself changes nothing, as on 1.7.10.
- Minecraft's default resource pack exists while mods load (Extra Utilities reads vanilla's
  bedrock texture from it for its Tinkers' Construct parts, and failed for every tool part).
- **Bedrock ores generate** (HBM's): they are written on 1.7.10's bottom layer, which the deep ore
  pass puts in the bedrock floor, where only stone and deepslate were accepted. The mod read the
  ore's tile entity back and crashed the chunk's generation.
- Legacy blocks and items whose names land on a vanilla id (a name without a mod id: "air") get a
  number at the end, as two legacy names that differ only in case do, instead of replacing
  vanilla's.
- **Runs on NeoForge 1.20.1** (47.1) as well as Forge: the same jar, client and server, tested with
  the play test's mods.
- **Coremods see the class loading they were written for.** While transformers run, the call stack
  is again frame for frame LaunchWrapper's: transformers that find their class loader by walking
  the stack (CoFHCore's) got the wrong one and failed on every Minecraft class, which stopped
  CoFHCore and the mods built on it. CoFHCore and MineFactory Reloaded boot and play.
- **Mixin on 1.7.10 (UniMixins) boots.** Loggers take log4j appenders again, and get every event:
  Mixin watches FML's log to know when to apply its mixins.
- More of log4j 2.0-beta9 that mods call (its resource `Loader`, used by CoFHCore to copy its
  world generation files), and the 1.7.10 framebuffer mods resize at startup.
- **Blocks that face where they were placed show it.** Plain-cube blocks whose icons depend on the
  world (furnaces and machines facing their placer, connected textures) are drawn in the world as
  1.7.10 drew them, not with their metadata's icons alone. Items keep their usual model.
- **1.20.1-only blocks work as covers.** Full-cube 1.20.1 blocks (deepslate, copper, other mods'
  blocks) reach 1.7.10 code as blocks with their real textures, so Carpenter's Blocks (and mods with
  facades or covers like it) accept and draw them. They turn back into themselves when they leave.
- **Sprite-sheet textures load.** Mods that cut their icons out of sheets with their own sprite
  loaders (Railcraft, Extra Utilities) were handed a file path where 1.7.10 hands them the icon's
  name, and gave up: hundreds of their blocks were the missing texture. They load as on 1.7.10.
- **Item renderers get the real stack.** Mods' item renderers were given only the item and damage,
  never the stack's NBT: BuildCraft's facades were invisible everywhere, and renderers that draw
  what a stack holds drew their default. They now get the stack itself.
- **Items whose icons depend on their NBT show them** (Tinkers' tools, FiskHeroes' suits, wands,
  17 mods of the tested ones use this): the item model asks the item for each stack's icons.
- **Icons from mods' own atlases** (FiskHeroes' suits, from its hero atlas) and **items showing
  block icons** (fluid containers) find their textures.
- **Compared against real 1.7.10.** A test-only 1.7.10 mod (`testmods/reference1710`) plays the
  same scenes in real Minecraft 1.7.10 as Retrofit's play test (items in the hand, blocks placed in
  the world, every item in a gallery), so the two are compared screenshot for screenshot. The fixes
  below came from those comparisons, each checked against 1.7.10's own code.
- **Items in the hand are posed as on 1.7.10**: item renderers that don't draw a block get 1.7.10's
  held-item pose (they floated beside the hand); OreSpawn's weapons, among others, now match
  1.7.10 exactly in first and third person.
- **Items in inventories are posed as on 1.7.10**: block items face the way 1.7.10 showed them (its
  GUI turns blocks a quarter further than 1.20.1's), and blocks drawn by their mods' item renderers
  are no longer off centre.
- **Block items are drawn as 1.7.10 drew them as items**: flat when 1.7.10 drew them flat (plants,
  rails, blocks drawn by their tile entity, which were invisible or tiny), by their renderers'
  inventory drawing with the right texture for each damage value, with the bounds of their item
  (pressure plates), and with the block's colour (leaves, grass).
- **Blocks smaller than a cube** drawn as plain blocks (cakes, carpets, plates) have their shape,
  in the world too; they were whole cubes.
- **Connected textures and other icons mods make from icons** (Chisel's pillars and circuits) use
  the icons' real places: mods take those at the stitch event, which comes again once the icons
  are placed, as on every 1.7.10 resource reload.
- **Vanilla's item drawing works inside mods' renderers** (FiskHeroes' charged items): the atlases
  are registered as 1.7.10 registered them, so RenderItem finds them by sprite number.
- **Legacy items are lit as 1.20.1 lights items.** Items drawn by their mods' renderers (and blocks
  drawn by their own block renderers) were drawn with mirrored poses whose normals came out wrong,
  so they were much darker than everything else in inventories and in the hand.
- **Items drawn by their renderers in inventories are lit, coloured and masked as on 1.7.10**:
  Forge drew flat ones unlit and in the stack's colour (GregTech's and IC2's items were half as
  bright), and the GL emulation now keeps face culling (models no longer show their insides) and
  drawing over what is already there only (GregTech's fluids in cells covered the whole slot).
- **Textures mods make from pixels show** (DragonAPI's sprite sheets: RotaryCraft's items and
  armor), and the GL calls taking doubles (`glRotated`, `glColor4d`, ...) and reading the matrix
  back work: they did nothing, so RotaryCraft's items were skewed.
- Block items whose damage picks the block without the item saying it has subtypes (IC2's sixteen
  foam walls) show and place each one, as the mod's item says.
- **Leaves follow the graphics setting** (fancy leaves are see-through), and blocks opaque only on
  fast graphics no longer hide the faces behind them.
- **Items that shimmer on 1.7.10** (enchanted, or always: Witchery's urns) have the glint; flat items
  whose hand is drawn by their mod's renderer are no longer shaded in GUIs.
- Galacticraft (and other mods whose items name an absent mod's API) no longer stop the client from
  starting: Retrofit asks about mods' methods one at a time, never the whole class.
- Other players' skins (and heads mods draw by player name) use their 1.20.1 skins.
- Extra Utilities no longer crashes the client when one of its machines is placed (it reads 1.7.10's
  sound manager, which Retrofit now provides, idle).
- Legacy blocks that draw something their block model can't show (untextured, with their own
  texture, or with an icon that has no file) are logged once each, and the icons without a texture
  file are named in the log. `-Dretrofit.debugItem=<name>` makes the harness print where an item's
  renderer draws in each render type, and the client log what such items draw in GUIs; the play test
  passes `-PdebugItem` and `-PdebugBlock` on.

## 0.4.1-beta

From players' reports (Thaumcraft 4, Rival Rebels, a large modpack):

- **Blocks keep their renderers' own light.** 1.7.10 renderers give each vertex a brightness
  (Thaumcraft's infused ores glow with it), which was lost, and quads were lit as if they faced up:
  blocks drawn by their mods' renderers came out dark, black in walls. Both are fixed for every
  block drawn by a mod's renderer.
- **Tile entity renderers draw where 1.7.10 drew them**: relative to the camera, so renderers
  that draw in world coordinates around the viewer work (Thaumcraft's aura nodes).
- **Camera-facing sprites show**: 1.7.10's view angles (ActiveRenderInfo) are set each frame.
- **In first person the viewer is the player**, as on 1.7.10: renderers that ask what the player
  wears or holds work (nodes show with Goggles of Revealing or a Thaumometer), and the client's
  1.7.10 player always has the current inventory.
- **Held items sway as on 1.7.10** (the arm's lag behind the view): Thaumcraft's Thaumometer no
  longer snaps around as the player turns.
- **Your skin on 1.7.10 models**: first-person arms (the Thaumometer) and other player models use
  the player's skin, in 1.7.10's 64x32 layout, instead of Steve.
- **Render tick drawing shows**: what mods draw at the end of a frame (Thaumcraft's aspects
  flying to the corner, research popups) is drawn over the HUD and screens.
- Two creative tabs with one name both appear (Rival Rebels' weapons tab was missing).
- Resource Loader (and mods that add resource packs in preInit) no longer stop 1.7.10 from
  starting.
- Retrofit is open source, under the GNU General Public License, version 2 (`LICENSE`); the mod
  metadata (`mods.toml`) says so too.
- `-Dretrofit.debugBlock=<name>` logs what legacy block and tile entity renderers draw.

## 0.4.0-beta

Played through the client the way players do (new client-driven play test), which showed most of
what made 0.3.x unplayable; all of these are fixed:

- **One mod no longer takes the others down.** A 1.7.10 mod that lacks a mod it requires (GregTech
  without IndustrialCraft 2), doesn't accept 1.7.10, or throws while loading is left out, with the
  mods that require it; every other mod loads, and the title screen says which mod was left out and
  why. Before, FML stopped and the game started with no 1.7.10 mods at all. A mod that fails while a
  world starts carries on (logged).
- **Broken event handlers are contained.** A 1.7.10 event listener that throws is logged once and
  the others still run. Before, one mod's handler could stop players being created (Galacticraft's
  handler did, which broke every client-side feature of every mod).
- **Keys reach 1.7.10 in the world.** Mods that poll the keyboard (IC2's jetpack, flight, hotkeys)
  saw nothing until a 1.7.10 GUI had been opened. 1.7.10's own key bindings (jump, sneak, movement,
  attack, use) now follow 1.20.1's, with the player's own bindings, and the local player's movement
  input too.
- **Mods move the player.** Motion and fall distance 1.7.10 code gives the player (jetpacks, flying
  armor, leaps, boots that soften falls) now move the 1.20.1 player; worn and carried items tick on
  the client too, as on 1.7.10.
- **Singleplayer counts as singleplayer.** 1.7.10's server now has an owner and online mode in
  singleplayer, and operators are the host's: mods that grant their protections to the
  singleplayer player or to operators (Carpenter's Blocks' covers, hammer and chisel) work.
- **Galacticraft**: its planets (the Moon, Mars, Asteroids), which it registers only once a server
  starts, are 1.20.1 dimensions; its server start no longer fails; rockets are placed on the launch
  pad, boarded and launched. Space stations (dimensions made while playing) aren't supported yet.
- **FiskHeroes 2.4** loads: Java 17 refused its classes ("Inconsistent constant pool data"), it
  needs Java 8's JavaScript engine for its heroes (Retrofit provides the standalone Nashorn, asked
  about like the other downloads), its sound packs download, and its HUD finds the player
  controller. Suits draw with their own models.
- **Armor with its own model** (Item.getArmorModel: hero suits, oxygen gear, robes, hats) draws that
  model on 1.20.1 players and creatures.
- **Armor textures**: textures at the top of a mod's assets (OreSpawn's armor) and armor textures for
  mods' own render prefixes are served; they were missing.
- **GregTech's machine items** draw in inventories and hands: block items were drawn with their
  block metadata (0-15) instead of the item damage 1.7.10 passed on, and blocks without icons got
  no item model at all.
- Mods' own negative GUI ids (Galacticraft's) no longer collide with Retrofit's: those GUIs open.
- Invisible blocks (render type -1: Galacticraft's lit air) no longer show as missing-texture cubes.
- 1.7.10 code sees the whole JDK again: javax.script, java.sql and the rest of Java's platform
  modules were invisible to it since Java 9.
- Mods' own texture maps stitch, and every resource reload listener runs even when one fails.
- Vanilla 1.7.10 spawn eggs convert to 1.20.1's.
- `-Dretrofit.debugInteractions=true` logs right and left clicks on legacy blocks and motion
  legacy code gives players.

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
