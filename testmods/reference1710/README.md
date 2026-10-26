# reference1710

A 1.7.10 Forge mod for **real** Minecraft 1.7.10, used to check how faithfully Retrofit plays 1.7.10
mods. It is never shipped and never loaded by Retrofit.

Started with `-Dretrofit.reference=<item list>`, it creates a flat world and plays the same scenes as
Retrofit's play test does with `-Pcompare=<item list>` (`retrofit.forge.test.PlayTest.compare`):

- a 15x15 stone platform at y 100, the player on it facing east, looking down 35 degrees, at noon;
- each item marked `hand` held in first person and from the front, after the hand has settled
  (`cmp-hand-N-first.png`, `cmp-hand-N-third.png`);
- the listed item blocks' blocks placed in front of the player, 35 at a time, as their items place
  them (`cmp-blocks-N.png`);
- every listed item in a gallery laid out as Retrofit's (`cmp-gallery-N.png`).

The two sets of screenshots line up shot for shot, so they can be compared side by side or pixel by
pixel. The item list has one stack per line, as 1.7.10 names it: `modid:name`, the damage and
optionally `hand`, separated by tabs (registry names may have spaces). The harness writes one for
the loaded mods with `-PextraJvmArg=-Dretrofit.itemList=<file>`, with up to 12 items per mod
marked `hand`.

`run_reference.py` starts the game for a run. Sound is left out (its OpenAL library isn't
unpacked): the scenes don't need it. 1.7.10 still believes its sound system started, so the driver
keeps it marked as not loaded, or the music ticker dies in OpenAL.

`ReferenceMain` starts 1.7.10 through LaunchWrapper like the launcher does, with the window placed
off the screen; the mod never grabs the mouse, so a run doesn't get in anyone's way. It needs Java 8
and the 1.7.10 and Forge 10.13.4.1614 libraries of a launcher install.
