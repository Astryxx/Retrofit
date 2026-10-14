package retrofit.forge.world;

/** A 1.20.1 entity standing for a legacy entity ({@link LegacyMob}, {@link LegacyThing}). */
public interface LegacyEntityCarrier {
    LegacyEntityCore core();

    /** Client: the legacy copy for rendering, or null. */
    default Object clientLegacy() {
        return core().clientLegacy();
    }
}
