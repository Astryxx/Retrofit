package org.apache.logging.log4j.core;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.message.Message;

/**
 * log4j-core's filter interface as Forge 1.7.10's log4j 2.0-beta9 had it, for mods that filter
 * log output (DragonAPI). Retrofit does not ship log4j-core; see {@link Logger}.
 */
public interface Filter {
    enum Result {
        ACCEPT, NEUTRAL, DENY
    }

    Result getOnMismatch();

    Result getOnMatch();

    Result filter(Logger logger, Level level, Marker marker, String msg, Object... params);

    Result filter(Logger logger, Level level, Marker marker, Object msg, Throwable t);

    Result filter(Logger logger, Level level, Marker marker, Message msg, Throwable t);

    Result filter(LogEvent event);
}
