# CrazyCraft 3 compatibility follow-up — 0.6.1-beta.2

## Reference and isolation

Reported against the official VoidLauncher CrazyCraft 3 pack copied into Forge 1.20.1.
The supplied friend log and crash report identify duplicate GraviGun and TrailMix entries
stopping FML initialization, followed by Forge's mismatch-screen null dereference. That client
registered zero legacy blocks, items, and tabs; the e4mc mixin in the stack was not the cause
established by those logs.

Tests use private copies of the supplied mod files and configs. The user's CurseForge instance
and saves were not modified. CodeChickenLib 1.1.3.141 was copied from its existing installed
runtime dependency folder. Tests include the hello1710 instrumentation mod only in development;
the production connection used the shipping jar, with no development test classes.

## Implemented fixes

- Duplicate identity comes from actual FML annotations, rather than example metadata. Missing
  metadata no longer lets a renamed copy evade duplicate detection. Formatting in declared
  Minecraft versions no longer excludes MrCrayfish's Furniture Mod.
- Air-material marker blocks retain air/replaceable semantics and null selection boxes.
- Player tick and living-update handlers receive the current inventory and their changes are
  written back before another synchronization replaces the mirror.
- Tabs use their own listing and naming methods. An index-specific title preserves different
  names even when two tabs share a label. Missing item translations receive readable fallback
  names, and cached display names follow language-resource reloads.
- Generated resource-pack images reach the modern renderer. Custom textures perform their own
  loading and deferred uploads; pixel changes receive increasing versions so a fallback can
  later become a downloaded skin, even if its name was previously cached as missing.
- Invisible ordinary faces remain invisible for TESR blocks such as the TARDIS, rather than
  drawing its diagnostic cube through the police-box model.
- Legacy tile initialization occurs before placement orientation. Modern Forge's subsequent
  deferred callback does not reset the tile a second time. Snapshot cancellation remains active.
- MCHeli's directory-based aircraft/weapon/HUD loaders read a private extracted copy of assets
  keyed by the archive's SHA-256; original archives are not changed or included in Retrofit.
- Previous-tick player positions and each frame's hit result reach world rendering, addressing
  two sources of placement-preview stepping/jitter.
- Legacy registry mismatches use a safe disconnect message instead of Forge's broken mod-name
  lookup. Registry compatibility checks are preserved.
- Shared vanilla icons requested by MalisisDoors retain the Malisis icon type and stitched UVs.

## Verification

The focused client scenario runs with `:forge:runClient -PplayTest -PcorpusDir=<private pack copy>`
and `-PextraJvmArg=-Dretrofit.crazyCraftTest=true` in the isolated test checkout.
It checks tab names and contents, the original placeholder-metadata collision, MCHeli item
registration, invisible Railcraft heat, creative inventory add and clear packets on both sides,
four Carpenter's slope facings, Forge placement cancellation, generated furniture images,
custom skin fallback pixels and texture refresh, and suppression of TARDIS cube faces.

The MCHeli 0.10.6 archive registers 116 items, compared with 12 before the fix. Slopes preserve
four distinct saved data values (8, 11, 9, 10), instead of all resetting to zero. A duplicate
selection regression using copies of the actual GravityGun/TrailMix jars keeps one of each and
keeps both Controlling and LuckyBlocks. Furniture and TARDIS screenshots were visually inspected;
the furniture has wood textures and the exterior has no colored diagnostic cube.

A separate production Forge 47.4.26 server reached Done and accepted the production client with
the copied pack and e4mc installed. The player remained connected about 90 seconds, until the
script ended the test. Later client-only preview and pixel-refresh adjustments were covered by
the focused client run, not another production connection. The e4mc public tunnel itself was
not exercised with a second person.

## Still needs follow-up

- MapWriter's white/blank minimap remains unresolved (the user marked it lower priority).
- Assets absent from the original jars remain absent: examples include EE3's WIP Dark Matter
  Bow and some unfinished TARDIS components. No replacement artwork has been fabricated.
- A complete TARDIS enter/exit journey with an owned, functional interior was not validated;
  the exterior and custom fallback lifecycle were tested separately.
- Moving Decocraft previews need a manual feel/visual recheck in the player's setup; the frame
  hit-result and interpolation plumbing were corrected, but this is not a measured motion test.
- SaintsCore still reports its missing packs data. Client-only Damage Indicators fails on a
  dedicated server and is left out there; Opis's headless client limitation also remains.
  Optional-mod recipe and obsolete update-service warnings are not a claim of full pack support.

This is a beta compatibility update, not certification that every item, GUI, dimension, and
mod interaction in CrazyCraft 3 has been exhaustively tested. Replace the old Retrofit jar on
both computers and keep only one version installed.
