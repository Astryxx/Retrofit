package retrofit.forge.world;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Legacy code that throws must never crash the game or disconnect anyone: callers catch, report
 * here and carry on with a neutral result. Each failing (thing, action) pair is logged once with
 * its stack trace, so a broken block can't flood the log.
 */
final class LegacyErrors {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private LegacyErrors() {
    }

    static void report(Object what, String action, Throwable error) {
        String key = what + "/" + action;
        if (REPORTED.add(key)) {
            LOGGER.error("Legacy {} failed while {}; carrying on (further errors of this kind are not logged)", what, action, error);
        }
    }
}
