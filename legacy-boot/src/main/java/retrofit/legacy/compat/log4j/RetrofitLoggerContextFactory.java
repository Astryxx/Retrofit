package retrofit.legacy.compat.log4j;

import org.apache.logging.log4j.spi.LoggerContext;
import org.apache.logging.log4j.spi.LoggerContextFactory;

import java.net.URI;
import java.util.function.Function;

/**
 * log4j-api provider for the legacy world (registered in META-INF/log4j-provider.properties).
 * Every legacy logger forwards to an SLF4J logger obtained from the host, so legacy logs go
 * through the host's own logging configuration.
 *
 * Loggers must be created by host code: log4j's SLF4J binding picks its logging context from
 * the calling class's loader, and a legacy caller would get a separate, misconfigured context.
 */
public class RetrofitLoggerContextFactory implements LoggerContextFactory {
    /** Set by the host before boot: logger name -> org.slf4j.Logger. */
    public static volatile Function<String, org.slf4j.Logger> hostLoggers;

    private static final RetrofitLoggerContext CONTEXT = new RetrofitLoggerContext();

    @Override
    public LoggerContext getContext(String fqcn, ClassLoader loader, Object externalContext, boolean currentContext) {
        return CONTEXT;
    }

    @Override
    public LoggerContext getContext(String fqcn, ClassLoader loader, Object externalContext, boolean currentContext,
                                    URI configLocation, String name) {
        return CONTEXT;
    }

    @Override
    public void removeContext(LoggerContext context) {
    }
}
