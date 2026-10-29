package retrofit.legacy.compat;

import java.util.UUID;

/**
 * Java 8's UUID.fromString, for legacy code: it read each of the five dash-separated parts as hex and took
 * what fit, where Java 9 and later refuse strings longer than 36 characters ("UUID string too large"). Mods
 * wrote such strings (More Planets' potion attribute modifiers) and worked on Java 8.
 */
public final class LegacyUUID {
    private LegacyUUID() {
    }

    public static UUID fromString(String name) {
        String[] components = name.split("-");
        if (components.length != 5) {
            throw new IllegalArgumentException("Invalid UUID string: " + name);
        }
        for (int i = 0; i < 5; i++) {
            components[i] = "0x" + components[i];
        }
        long mostSigBits = Long.decode(components[0]);
        mostSigBits <<= 16;
        mostSigBits |= Long.decode(components[1]);
        mostSigBits <<= 16;
        mostSigBits |= Long.decode(components[2]);
        long leastSigBits = Long.decode(components[3]);
        leastSigBits <<= 48;
        leastSigBits |= Long.decode(components[4]);
        return new UUID(mostSigBits, leastSigBits);
    }
}
