# ManaMetal and combat follow-up: 0.6.1-beta.3

## Reference and isolation

ManaMetal 8.0.7 with Muya 1.11.1 is tested by itself, apart from development-only hello1710
instrumentation. Other scenarios use separate processes and corpus selections. Tests use private
copies and throwaway worlds; the player's CurseForge mods, configurations, and saves are untouched.

The Queen compatibility reference is OreSpawn 20.3 and Chaos Persists 0.9.1 for Forge 1.20.1,
[official file 9130290](https://www.curseforge.com/minecraft/mc-mods/chaos-persists/files/9130290).
The reporter's exact Chaos Persists build is unknown; this version was selected from the current
public release listing, rather than assumed to match their installation.

## Changes and evidence

- Player-join events no longer run while holding the player-mirror map monitor. A thread dump
  identified a server/render-lock deadlock while ManaMetal built its newcomer village.
- Legacy world spawn queries use the actual host spawn, rather than an unrelated generated spawn.
  The mod's original village and spawn-point tile perform tutorial entry; no forced tutorial
  teleport has been added to Retrofit.
- Original pickup and pre-harvest handlers receive modern item pickups and block breaks, preserving
  cancellation, experience changes, remaining drops, and inventory writeback. A three-coin drop
  enters its profession wallet, disappears from the world, and does not enter inventory. The wallet
  also reaches the client. Breaking stone with a pickaxe decreases fatigue.
- Motion from legacy client packets and tick handlers reaches the actual player. Fall-distance
  updates carry a separate motion-change flag so they cannot restore an older velocity. The dash
  check sends the original request while walking and observes actual movement and stamina use.
- The death button sends the original MessageKey(-1) packet and produces an actual player death.
  Original damage-source identity survives the synchronous host callback; absolute creative-capable
  kill damage uses the modern kill source, bypassing protection that otherwise left the player at 1 HP.
- Lightmap texture enable/bind state is separate from the main font and item atlas. The class menu's
  text is readable. Unused consumables use zero-count GUI stacks; drawing-only copies preserve their
  icons and zero labels without changing inventory semantics. The panel exposes 31 available entries.
- Item-count overlays use the icon's scale. Legacy custom tooltips can also be requested for modern
  mod items. The tooltip test exercises the original drawing function and font-page glyphs.
- ManaMetal's health HUD includes its own food meter. The independent modern hunger overlay is
  suppressed while that replacement is active. The test observes actual modern overlay cancellation,
  and its screenshot is inspected; the custom meter remains visible without vanilla drumsticks.
- Ordinary host attacks cannot bypass ManaMetal's own failed weapon-eligibility verdict. Both an
  ineligible scythe and an eligible starting weapon are tested against modern targets.
- The scythe orb is verified in its deferred world-effects pass, separately from its textured particle
  trail. The original warrior slash renderer selects four distinct texture frames; the frames are also
  drawn through the host renderer for visual inspection. This is not a claim that every skill was tested.
- AoA's original onEntitySwing callback creates greatblade/scythe attacks and damages modern targets.
- Erebus and Betweenlands natural-spawn checks find their own creatures: 15 Erebus species and 8
  Betweenlands species in the sampled runs. Their custom biome-provider and terrain fixes are retained.
- Queen combat is observed against the actual Chaos Persists TheQueen class, not a similarly named
  helper entity. A forced host health write reproduced a stale client death pose while the server
  Queen was alive. Health is synchronized before and after animation ticks; real dying state is
  synchronized separately so the host's temporary positive-health placeholder cannot revive it.
- Core regressions check direct damage/heals, blast motion without double knockback, follow range,
  actual legacy death animation and removal, modern equipment, Mob Battle interactions, below-zero
  creatures and ores, runtime dimensions, and legacy world-generation containers.
- The prior CrazyCraft scenario checks creative add/clear synchronization, four Carpenter's facings,
  cancellable placement, invisible Railcraft heat, generated furniture, custom skins, and TARDIS faces.

## Final verification

The full isolated normal-terrain survival ManaMetal run passed all 20 checks (`mana-final-3.log`).
A separate fresh flat creative world passed the three tutorial-only checks. Queen combat passed
seven checks against Chaos Persists 0.9.1; the core run also preserved a real dying creature's
animation and removal. Erebus/Betweenlands passed nine spawn checks. The final CrazyCraft rerun
passed all 19 checks, including inventory add/clear, directional placement, heat, and furniture.
The final AoA rerun passed all seven checks. The server API audit checked 2,033 references with
zero reported problems. The build and standalone harness smoke run passed.

The shipping jar was built with Java 17. Native Forge 47.4.26 dedicated-server/client tests used
that jar without development test classes. CrazyCraft joined and remained connected for about
86 seconds. Isolated ManaMetal joined, reported `retrofit:legacy_dim_105` from the server console,
and remained connected for about 110 seconds. Both servers shut down cleanly without a crash.
The ManaMetal server test used `allow-flight=true`: with flight disabled, the tutorial visit was
kicked by vanilla's flying check. That remains a configuration requirement, not a claimed fix.

Earlier attempts that repeatedly hid the native client window timed out during login. The visible
native-client retry joined successfully. Those failed attempts are not counted as passing evidence.
Production startup also exposed a 1.7.10 client-only selection-box call. Shared shape queries now
catch that method's absence on dedicated servers while retaining collision-box computation.

## Reproducing focused development checks

Use Java 17 and a private corpus. The source ZIP contains the development tests; the player jar
excludes them. Supply a separate client game directory to keep each lab independent.

```text
:forge:runClient -PplayTest -PmanaReview=true -PtestWorld=normal -PtestGameMode=survival -Pcorpus=manametal,muya -PclientGameDir=<lab>
:forge:runClient -PplayTest -PmanaReview=true -PmanaTutorialOnly=true -PtestGameMode=creative -Pcorpus=manametal,muya -PclientGameDir=<lab>
:forge:runClient -PplayTest -PqueenReview=true -Pcorpus=orespawn -PmodernMods=chaospersists -PclientGameDir=<lab>
:forge:runClient -PplayTest -PaoaReview=true -Pcorpus=nevermine -PclientGameDir=<lab>
:forge:runClient -PplayTest -PdimensionReview=true -Pcorpus=erebus,betweenlands -PclientGameDir=<lab>
:forge:runClient -PplayTest -Pcorpus=none -PmodernMods=mobbattle -PmobBattleRepeats=3
```

A Gradle exit code alone is insufficient: require the scenario's Done line, no FAILED checks, and
no thrown scenario actions. Earlier diagnostic failures remain private evidence, not passing runs.
The dungeon assertion compares expected template blocks at their own coordinates; unrelated terrain
cannot inflate its count.

## Limits

These are focused compatibility checks, not an exhaustive ManaMetal playthrough. Every class,
advanced skill, dungeon, crafting progression, network latency, and combination of modern mods has
not been exercised. The full animation-report reproduction through the skill-selection menu remains
broader than the four-frame renderer check. No assets absent from the original mod are fabricated.
The older CrazyCraft follow-up lists remaining MapWriter, TARDIS journey, and preview-feel limitations.
The e4mc public tunnel is separate from a local production server/client connection test.

Install the same beta.3 jar on all clients and the server; the added legacy dying-state field requires
matching builds. Keep only one Retrofit jar. The source archive contains project sources and the Gradle
wrapper, excluding game/mod jars, private references, caches, worlds, logs, and the original uploaded ZIP.
