# 0.6.2-beta major-report follow-up

Version remains **0.6.2-beta**. This work extends the ManaMetal/combat fixes from the previous
0.6.2-beta snapshot. Install the same newly built jar on the server and every client.
Private reference jars are OreSpawn 20.3 and The Titans 0.45; the Queen combat regression also
uses modern Chaos Persists 0.9.1. No original game/mod code or private references are distributed.

## Changes and boundaries

| Report | Result |
| --- | --- |
| Boss/minion maximum health capped at 1,024 | Reproduced and corrected per legacy creature. Queen server/client maximum is 6,000; Zombie Titan is 20,000. Modifier operation order, removal invalidation, permanent modifier reload, and an old capped host save are covered. Modern cow bounds remain 1,024. Supplied Titans 0.45 Templar is originally 650 HP and stays 650. |
| Raw text IDs in native HUDs | Native locale loading now merges FML's code-registered translations. Mantis, the actual `entity.The Queen.name` key, and Mobzilla resolve in native I18n/StatCollector/LanguageRegistry. An audit covered 790 code-registered entity/item/tab names without raw keys. This does not fabricate translations absent from a mod. |
| OreSpawn Thunder Staff | Original ThunderBolt was never registered in EntityList and the bridge discarded it. Unregistered throwables now tick transiently on each side where the original mod spawned them. Tests observe both projectiles, target damage, one durability use, server impact removal, and client solid-block cleanup. Original EntityThrowable deliberately skips client entity impacts; that behavior is preserved. |
| Mantis FPS / stacked green boss bars | Exact report remains unreproduced. The user does not know the modpack, versions or HUD provider. Supplied Mantis has 120 HP; the screenshot's 1,024 HP is not its default. Controlled tests include a process-local 2,000-HP variant, actual OreSpawn Unstable Ant dimension (lab ID 83), warmed enclosed scene, fixed camera, and verified 0/16/64 populations on both sides. A model vertex submission optimization reduced sampled 64-Mantis render-thread CPU from 5.35 to 4.07 ms/frame; capped frame time remained about 8.46 ms. This is scene-specific, not a universal FPS fix or a reproduction of the stacked bars. |
| Titans eggs after death | Not reproduced with the supplied pair. Actual client zombie-Titan egg clicks before death and after two real kill/respawn cycles each spawn one Titan and synchronize stack count 2 to 1. No speculative egg/lifecycle gameplay change is made. Other versions, eggs and unknown packs remain unverified. |

## Additional issues found during verification

- Old client levels and native player maps retained departed worlds through value-to-key reference
  cycles despite WeakHashMap keys. Cleanup now runs after native unload listeners, releases owned
  world/entity/player/spawner/tile caches, and does not invoke native creature death/drop routines.
  The focused test checks both legs of a dimension trip and subsequent creative add/clear packets.
- The more demanding dimension regression exposed player construction holding the shared mirror-map
  monitor while a dimension spawn provider generated chunks. Construction and saved-state hooks
  now run outside that monitor; map lookup/publication remain synchronized.
- Sky and atmosphere renderers run before the ordinary AFTER_SKY camera setup. Prepare their own
  viewer before calling native rendering, including the first frame after a dimension transition.
- Dedicated servers without LWJGL 2 can generate neutral keyboard/mouse shims from Retrofit's own
  input API. Key code zero returns the original NONE name. The headless fixture hides all original
  LWJGL resources. More Health Enhanced then reaches a separate invalid server-side GUI reference;
  its login handler is **not** claimed fully repaired.

## Evidence and rejected interpretations

All game checks use private test copies. A passing Gradle exit alone is insufficient: require the
scenario Done line, no FAILED assertions, and no thrown scenario steps. Detailed final run results
accompany the deliverables. Production joins are connection smoke tests, not complete multiplayer
playthroughs. ManaMetal's production tutorial requires `allow-flight=true` in the tested setup.

The initial Mantis benchmark was discarded because screenshots showed creatures had fallen off
the platform. Enclosed-room replacement measurements verify actual populations. Twenty-four
transformed vertex comparisons preserve positions, normals, colors, alpha, UVs and packed light/
overlay against Minecraft's existing API. The fixed-camera before/after keeps all other source
changes equal; production creature AI, spawn rates and health defaults are unchanged.

An early Queen translation assertion used a nonexistent key; the corrected fixture derives the
actual registration. An early Thunder Staff assertion expected client entity collision that the
original mod never performs; the experimental proxy change was removed. A dimension run with a
sky initialization failure and constructor stall was retained as failure evidence and not passed.
One native ManaMetal connection attempt timed out; only successful final retries count.

## Remaining beta limits

The unknown Mantis pack/HUD reproduction and unknown Titans egg setup remain open. MapWriter's
blank minimap, original missing/WIP assets, More Health Enhanced's dedicated-server GUI call,
older client-only pack initialization failures, full TARDIS journeys, and e4mc tunnel behavior
remain outside the passing focused checks. Broad ManaMetal class/skill progression and every
possible modern-mod combination have not been exhaustively tested. Do not interpret this record
as a guarantee that every compatibility issue is resolved.

## Regression results (2026-10-30)

| Focused scenario | Passing assertions |
| --- | ---: |
| Health/modifiers/save upgrade, native names, Thunder Staff, eggs, unload and inventory | 38 |
| Isolated ManaMetal survival tutorial, HUD, RPG state, tooltips and effects | 20 |
| Queen with Chaos Persists 0.9.1 | 7 |
| AoA greatblade and scythe | 7 |
| Erebus / Betweenlands terrain, teleports, flora and natural spawns | 9 |
| CrazyCraft tabs, textures, placement, aircraft and creative inventory | 19 |
| Core combat, genuine death, Mob Battle repeated three times and world generation | 47 |

These 147 assertions completed their scenario Done lines without FAILED checks or thrown steps
in the retained passing runs. The final dimension retry also rendered the native Betweenlands sky
(62,096 captured vertices) without the earlier sky initialization failure. Separate build,
clean-Forge linkage and no-LWJGL headless-input checks passed. The corrected background egg fixture
refreshes Minecraft's normal cursor pick after aiming; an earlier stale-pick failure is not passed.

Final production checks used the rebuilt player jar in native Forge 47.4.26 server/client pairs:
CrazyCraft stayed connected for 132 seconds after its expected config-sync restart, and isolated
ManaMetal stayed connected for 212 seconds in `retrofit:legacy_dim_105`. Both servers shut down
cleanly; no client/server crash was recorded. The production console reported computed Queen
MAX_HEALTH and current Health as 6,000, verifying the per-instance attribute in the reobfuscated
jar. Earlier minimized-client login timeouts are excluded; the private launcher was corrected to
show/restore its own game window. This is a smoke test, not a claim that all pack handlers succeed.

## Additional hardening and stronger tutorial verification

The later hardening pass adds bounded texture capture with reusable per-texture storage and
opt-in read-only JVM memory diagnostics. See `COMPATIBILITY_HARDENING_062_BETA.md` for the
before/after failures, 18 texture checks, measured Mantis samples and rejected changes.
`HYBRID_RUNTIME_REVIEW.md` describes future runtime boundaries; no second-version backend ships.

A fresh-world test exposed a tutorial landing defect that the earlier dimension-only assertion
missed. The bridge now carries native coordinates and rotation through queued dimension travel.
The stronger survival suite passes 22 assertions, creative tutorial checks pass 5, dimensions pass
9, major-report checks pass 38, and core/Mob Battle checks pass 47. All complete their Done lines
without failed assertions. Previously retained focused checks remain separately identified above.

The updated shipping jar also joins the native CrazyCraft production server. A fresh ManaMetal
production retry enters dimension 105 at (3.5,6.5,213), settles at (3.5,6,213), and reports ground
beneath the player on three probes. The initial production login timeout remains failed evidence;
it is not included among the passing joins. Existing below-world test saves are not automatically
repaired by this handoff fix. Broad progression, unknown pack reproduction and the limits listed
above remain beta work.
