package retrofit.legacy.compat.log4j;

import org.apache.logging.log4j.message.MessageFactory;
import org.apache.logging.log4j.spi.ExtendedLogger;
import org.apache.logging.log4j.spi.LoggerContext;
import org.apache.logging.log4j.spi.LoggerRegistry;
import org.apache.logging.slf4j.SLF4JLogger;

import java.util.function.Function;

/** The single legacy logging context; see {@link RetrofitLoggerContextFactory}. */
final class RetrofitLoggerContext implements LoggerContext {
    private final LoggerRegistry<ExtendedLogger> loggers = new LoggerRegistry<ExtendedLogger>();

    @Override
    public Object getExternalContext() {
        return null;
    }

    @Override
    public ExtendedLogger getLogger(String name) {
        return getLogger(name, null);
    }

    @Override
    public ExtendedLogger getLogger(String name, MessageFactory messageFactory) {
        ExtendedLogger logger = loggers.getLogger(name, messageFactory);
        if (logger != null) {
            return logger;
        }
        Function<String, org.slf4j.Logger> host = RetrofitLoggerContextFactory.hostLoggers;
        if (host == null) {
            throw new IllegalStateException("Retrofit: legacy logging used before the host connected it");
        }
        // Wrapped as log4j-core's Logger type for mods that cast to it (see org.apache.logging.log4j.core.Logger).
        SLF4JLogger slf4j = new SLF4JLogger(name, messageFactory, host.apply(name));
        loggers.putIfAbsent(name, messageFactory, new org.apache.logging.log4j.core.Logger(slf4j, name, slf4j.getMessageFactory()));
        return loggers.getLogger(name, messageFactory);
    }

    @Override
    public boolean hasLogger(String name) {
        return loggers.hasLogger(name);
    }

    @Override
    public boolean hasLogger(String name, MessageFactory messageFactory) {
        return loggers.hasLogger(name, messageFactory);
    }

    @Override
    public boolean hasLogger(String name, Class<? extends MessageFactory> messageFactoryClass) {
        return loggers.hasLogger(name, messageFactoryClass);
    }
}
