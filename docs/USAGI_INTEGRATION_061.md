# Usagi integration — 0.6.1-beta

Incoming reference: `Retrofit-main.zip` supplied on October 28, 2026.
The archive is based on the older 0.6.0 tree; it was compared against the
current 0.6.1 working tree rather than replacing that tree wholesale.

## Integrated

- Direct motion-field writes on modern creature stand-ins reach the host as
  velocity changes, once. Pushes made before damage survive the damage sync.
- The proxy generator contains the same changes as its generated Java source.
- Entity tracking uses the range registered by the legacy mod.
- Client entity updates restore authoritative health after client animation ticks.
- Direct host health increases reach the legacy entity as heals. Calls already
  forwarded by the normal heal method are acknowledged to prevent double healing.
- Modern targeting retains the current attack/revenge/old-AI target bridge, plus
  Usagi's single follow-range modifier.

Existing fixes for stand-in targeting, potion clearing, projectile attribution,
failed-mod registry filtering, multiplayer IDs and client lifecycle were retained.
Build caches, compiled files, old changelog replacements and the removal of the
local Java installation setting were not imported from the archive.

## Verification

The focused client run with Mob Battle passed blast velocity transfer,
single follow-range application, direct healing, normal healing without
duplication, client health sync, and the Mob Battle attack/group/heal/effect/kill
scenarios. The focused OreSpawn/Rival Rebels run also passed the new motion and
healing checks and OreSpawn combat assertions before its shutdown was interrupted.
The latter is not recorded as a completed full-pack run.

`forge:build` and `testmods:hello1710:build` succeeded. Regenerating the proxy
source produced identical contents.

## Visual correction

The Ruby Golem used in Mob Battle tests paired a generic 64x32 biped model with
the 64x64 zombie skin. A client capture reproduced a floating head and missing
body parts. Its test renderer now uses the matching zombie model, with a dedicated
visual scenario. This correction belongs to the development test mod; the test
mod is not bundled in the player jar.

The corrected client capture shows the head, torso, arms and legs again.

## Group-target test correction

Temporary tracing reproduced the group failure in `EntityAITarget.resetTask`:
the target was alive but 15.48 blocks away, beyond that golem's 15.28-block follow
range. The original group test reused fighters that wandered during the earlier
stick battle, so its outcome depended on their positions and randomized follow
range. This was an invalid test setup, rather than a dropped bridge call.

The group phase now discards the previous pair and spawns a fresh golem and husk
four blocks apart. The first golem remains for the separate healing check. No
legacy AI distance rule is overridden. `-PmobBattleRepeats=8` repeats the complete
client-driven battle scenario for regression checking.

These focused results do not establish that every creature in the corpus renders
correctly. Production multiplayer was tested before this contributor integration;
it has not been rerun for this build.

Final repeated client verification: 79 passed, 0 failed; all eight
group-item scenarios passed. Both README.md and CHANGELOG.md document the cause
and correction. Temporary target-clear tracing was removed.
