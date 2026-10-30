# Compatibility hardening evaluated for 0.6.2-beta

Independently implemented changes, selected using executable checks against Retrofit.

## Decisions based on executable checks

| Candidate | Finding in Retrofit | Decision |
| --- | --- | --- |
| Texture upload buffer hardening | A short RGBA ByteBuffer reproducibly throws IndexOutOfBoundsException in the real GL11 capture path. Full same-size uploads also replaced their capture array each time. | Implemented bounds/size handling and per-texture pixel-storage reuse. Full uploads clear missing pixels; subimages preserve surrounding pixels. Source cursor/limit, supported channel formats, cache invalidation and snapshot ownership are tested. |
| Native direct-memory watchdog | Sixty-second measured samples with 0/16/64 actual high-health Mantis creatures did not exhibit runaway JVM direct-buffer growth. | Added opt-in read-only telemetry and performance snapshots. Rejected automatic System.gc and the arbitrary 1 GiB intervention threshold for normal gameplay. |
| Recipe-sync filtering | The actual Forge 1.20.1 recipe-sync packet round-trips empty results and empty ingredient encoding without an exception; Retrofit already filters unresolved generated recipes. Its special native crafting dispatcher intentionally has no fixed display output. | Do not import 1.21-oriented recipe deletion or mutate the server's global recipe manager. Retain a version-specific packet regression check. |
| Raw GL pixel-pointer / unpack-state helpers | Retrofit captures supported pixels into Java arrays and uploads through the host, rather than handing the old caller's pointer straight to the driver. | Use capture bounds checks and storage reuse. Driver-specific GL state changes and an extra direct scratch-buffer layer do not apply to this path. No claim that this repairs blank MapWriter. |
| Jade, Curios, custom NEI-to-JEI adapters | Useful feature references, but use replacement API types and change library loading, accessory state and integrations. Ordinary recipe viewing already exists in Retrofit. | Defer to separate tested features. Do not replace working old libraries as a stabilization side effect. See the hybrid review. |
| Versioned synthesis, virtual fields and library isolation | Useful architectural patterns; they require a separate shim object model. | Record a staged multi-version design, with a class-loader boundary fixture; do not import replacement engine classes into the existing 1.7.10 realm. |

## Texture evidence

The pre-change allocation test fails on replacement of the pixel backing array. A separate
pre-change malformed-buffer run fails at GL11.put with IndexOutOfBoundsException. These are
failure evidence, not passing tests.

`:legacy-boot:checkTextureUploads` now passes 18 checks including 500 same-size uploads without
replacement pixel arrays, heap/direct/read-only buffers, RGBA/BGRA/packed-ARGB formats, nonzero
positions and limits, truncated buffers, null redefinition, clipped subimages, extreme offsets,
invalid dimensions, resize/delete, host snapshot isolation and upload-version invalidation.
Incomplete pixels become transparent black, avoiding the stale-tail problem that simple scratch
buffer reuse can introduce. Valid input values retain their existing behavior. This is a robustness
and allocation fix, not a demonstrated FPS cure for an unknown modpack.

## Mantis/direct-memory measurements

Private OreSpawn 20.3 + Titans 0.45 lab, actual Unstable Ant dimension (lab ID 83), verified enclosed
populations on client and server, fixed camera, 120-FPS cap and 2 GiB heap. The process-local Mantis
health variant is 2,000; original mod defaults are restored. Each sample measures 1,200 ticks
(about 60 seconds) after warming. JFR is enabled in this development test.

| Mantis count | Mean frame ms | p95 frame ms | Direct bytes at start | Direct bytes at end | GC time added (ms) |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 0 | 8.478 | 9.786 | 28,726,167 | 30,741,895 | 7 |
| 16 | 8.463 | 10.005 | 29,140,361 | 30,125,972 | 8 |
| 64 | 8.463 | 10.786 | 28,458,900 | 28,458,905 | 25 |

The 64-Mantis sample ends five bytes above its start, not evidence supporting a 1 GiB forced-collection
watchdog threshold. This test does not reproduce the original unknown pack/HUD or rule out a
problem with larger heaps, longer sessions or other mods. JVM NIO pools also exclude some native
allocator/GPU memory. Do not interpret these samples as attribution of all process memory.

For affected-pack diagnostics, launch with `-Dretrofit.memoryLog=true` (development run:
`-PmemoryLog=true`). It logs heap, direct/mapped NIO usage, buffer count and GC totals every five
seconds. It never requests collection, changes heap limits or introduces a background watcher.
Telemetry is off by default. `:host:checkRuntimeMemory` verifies that it observes a retained 2 MiB
NIO allocation. Fix actual allocation sources before considering a forced-collection workaround.

## Recipe/version evidence

The real ClientboundUpdateRecipesPacket preserves a normal diamond recipe, decodes empty output
and empty-ingredient examples, and retains the special LegacyRecipes.Crafting dispatcher. The
focused candidate client reports all four packet assertions passing. This demonstrates a version
boundary in the proposed fix, not proof that all malformed packets are safe or that the earlier
e4mc crash is resolved. No vanilla or other modern mods' recipes are deleted.

## Hybrid follow-up

See `HYBRID_RUNTIME_REVIEW.md` for the subsystem matrix, current single-runtime blockers,
recommended ownership model and migration sequence. `:host:checkRuntimeIsolation` proves the
fixture's class identity/static-state separation and shared neutral interface. It is not a working
second-version Minecraft backend. 0.6.2-beta still supports its existing 1.7.10 runtime only.

## Additional tutorial landing issue found during verification

A reused production character was below the tutorial world. A fresh-world check then confirmed
that dimension entry alone was insufficient: ManaMetal had selected a landing near (3.5,6.5,213),
but the host arrived near (-0.5,-0.6,-0.5) outside the academy and without a floor.

The original mod writes its native player's coordinates before calling dimension transfer. The
bridge now snapshots those feet coordinates and rotation when transfer is requested, preserving
them through the host operation and the original teleporter. The fresh survival check now arrives
at (3.5,6,213), with collision geometry beneath the player. This is a generic coordinate handoff,
not a ManaMetal-specific forced destination or a fabricated platform.

The tutorial regression now asserts original expected coordinates and a solid landing, in addition
to checking the dimension. The earlier dimension-only check missed this issue and is not evidence
of a correct landing. Other custom teleporters and full gameplay regressions are repeated after
the coordinate-handoff change.
