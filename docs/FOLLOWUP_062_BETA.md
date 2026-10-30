# Reports queued during 0.6.2-beta verification

These investigations are separate from the verified ManaMetal/combat fixes. None of the reports
below is claimed fixed by 0.6.2-beta. Private diagnostics use the supplied OreSpawn 20.3 and
The Titans 0.45 jars, without Chaos Persists. No original mod bytecode is distributed.

| Report | Evidence so far | Next useful check |
| --- | --- | --- |
| Bosses/minions capped at 1,024 HP | Reproduced the shared cap on the Queen: native maximum 6,000; modern maximum and displayed health 1,024 after ticking. A normal overworld Mantis remained 120/120. The host copies native max health into the modern MAX_HEALTH attribute. | Correct the modern attribute bound without altering original boss damage rules; test Queen, Titans, minions, saves and multiplayer. A global bound change requires regression coverage. |
| Unstable Ant dimension FPS near many Mantis spawners | A 30-second JVM sample with 16 Mantis creatures completed. It contained 173 render-thread and 78 server-thread execution samples, dominated by chunk preparation/rendering. No sampled monitor-contention events. This was an overworld baseline, not a reproduction of the supplied dimension/spawner scene. | Repeat in the actual Unstable Ant dimension with warmed chunks, fixed camera and entity counts; compare no mobs, one Mantis and crowded spawners. Do not infer a fix from this baseline. |
| Thunder Staff does not work | Its original handler ran: the staff has 50 durability, consumed one use and applied recoil. A delayed entity query found no ThunderBolt. That query cannot distinguish failure to spawn from an immediate collision/removal. | Observe the original projectile creation and impact separately in open air, then verify damage and visible rendering against targets. |
| Titans eggs stop working after death | The original zombie-Titan egg spawned before death and again after a real kill/respawn in a disposable world. This used server-side item use; it does not rule out a client click/prediction or pack-specific failure. | Repeat through normal client clicks after repeated deaths with the reporter's pack and compare ordinary eggs. Check native player lifecycle state. |
| Raw text IDs on bosses and other UI | Modern names resolved as Mantis and The Queen in the baseline. The screenshot's raw entity.Mantis.name therefore also needs investigation in the original mod HUD path. OreSpawn's supplied jar has no .lang files; it registers translations in code. Retrofit copies those into the modern language pack. | Compare the native client localization table with the modern pack, including code-registered keys, locale switching and resource reload. Audit tabs/items and boss HUDs separately. |

The private test fixture and logs are retained in the workspace for continued investigation. They
are diagnostic observations, not additional passing release assertions. The first diagnostic had
an incorrect bridge-method name and was discarded; one retry ran out of Windows paging space.
The bounded-heap retry completed. Neither failed run is counted as release validation.
