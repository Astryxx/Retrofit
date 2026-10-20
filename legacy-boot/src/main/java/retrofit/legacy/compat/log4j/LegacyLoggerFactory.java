package retrofit.legacy.compat.log4j;

import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;

import java.util.function.Function;

/**
 * Replaces {@code org.slf4j.LoggerFactory} for legacy code (Netty 4.0 uses SLF4J when it
 * can see it). The SLF4J API is shared with the host, but loggers must be created by host
 * code; see {@link RetrofitLoggerContextFactory}.
 */
public final class LegacyLoggerFactory {
    private static final ILoggerFactory INSTANCE = new ILoggerFactory() {
        @Override
        public Logger getLogger(String name) {
            return LegacyLoggerFactory.getLogger(name);
        }
    };

    private LegacyLoggerFactory() {
    }

    public static Logger getLogger(String name) {
        Function<String, Logger> host = RetrofitLoggerContextFactory.hostLoggers;
        if (host == null) {
            throw new IllegalStateException("Retrofit: legacy logging used before the host connected it");
        }
        return host.apply(name);
    }

    public static Logger getLogger(Class<?> type) {
        return getLogger(type.getName());
    }

    public static ILoggerFactory getILoggerFactory() {
        return INSTANCE;
    }
}
