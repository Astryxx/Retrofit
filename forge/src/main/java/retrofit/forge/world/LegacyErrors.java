package retrofit.forge.world;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Legacy code that throws must never crash the game or disconnect anyone: callers catch, report
 * here and carry on with a neutral result. Each failing (thing, action) pair is logged once with
 * its stack trace, so a broken block can't flood the log.
 */
public final class LegacyErrors {
    /**
     * By name, not LogUtils.getLogger's stack walk: this class is first used where legacy code failed, which
     * can be deep in an overflowing stack, and a class whose initialisation fails is gone for the rest of the
     * game (every later report then crashed it). RetrofitMod also initialises it at startup ({@link #ready}).
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(LegacyErrors.class);
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private LegacyErrors() {
    }

    /** Initialises this class (see LOGGER). */
    public static void ready() {
    }

    static void report(Object what, String action, Throwable error) {
        String key = what + "/" + action;
        if (REPORTED.add(key)) {
            LOGGER.error("Legacy {} failed while {}; carrying on (further errors of this kind are not logged)", what, action, error);
        }
    }

    /** Whether anything was reported for {@code what} ("legacy dimension 66"), for the play test. */
    public static boolean reportedFor(String what) {
        String prefix = what + "/";
        return REPORTED.stream().anyMatch(key -> key.startsWith(prefix));
    }
}
