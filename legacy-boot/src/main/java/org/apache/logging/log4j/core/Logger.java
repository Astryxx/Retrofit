package org.apache.logging.log4j.core;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.message.Message;
import org.apache.logging.log4j.message.MessageFactory;
import org.apache.logging.log4j.spi.ExtendedLogger;
import org.apache.logging.log4j.spi.ExtendedLoggerWrapper;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Every legacy logger is one of these, so mods that cast loggers to log4j-core's Logger (to add
 * filters or change levels, as DragonAPI does, or to watch its events with an appender, as Mixin
 * does) keep working. Messages go on to the host's logging (see retrofit.legacy.compat.log4j);
 * this class only adds filters, appenders and a level. It is not log4j-core: no configuration or
 * lookups, and appenders see events without writing anywhere themselves.
 */
public class Logger extends ExtendedLoggerWrapper {
    private final List<Filter> filters = new CopyOnWriteArrayList<Filter>();
    private final List<Appender> appenders = new CopyOnWriteArrayList<Appender>();
    private volatile Level level;

    public Logger(ExtendedLogger delegate, String name, MessageFactory messageFactory) {
        super(delegate, name, messageFactory);
    }

    public void addFilter(Filter filter) {
        filters.add(filter);
    }

    public void removeFilter(Filter filter) {
        filters.remove(filter);
    }

    public Iterator<Filter> getFilters() {
        return filters.iterator();
    }

    public int filterCount() {
        return filters.size();
    }

    public void addAppender(Appender appender) {
        appenders.add(appender);
    }

    public void removeAppender(Appender appender) {
        appenders.remove(appender);
    }

    public Map<String, Appender> getAppenders() {
        Map<String, Appender> byName = new LinkedHashMap<String, Appender>();
        for (Appender appender : appenders) {
            byName.put(appender.getName(), appender);
        }
        return byName;
    }

    public void setLevel(Level level) {
        this.level = level;
    }

    @Override
    public Level getLevel() {
        Level own = level;
        return own != null ? own : super.getLevel();
    }

    public void setAdditive(boolean additive) {
        // no logger hierarchy here
    }

    public boolean isAdditive() {
        return true;
    }

    @Override
    public void logMessage(String fqcn, Level messageLevel, Marker marker, Message message, Throwable t) {
        Level own = level;
        if (own != null && !own.isLessSpecificThan(messageLevel)) {
            return;
        }
        Event event = filters.isEmpty() && appenders.isEmpty() ? null : new Event(getName(), fqcn, messageLevel, marker, message, t);
        if (!filters.isEmpty()) {
            for (Filter filter : filters) {
                try {
                    Filter.Result result = filter.filter(event);
                    if (result == Filter.Result.DENY) {
                        return;
                    }
                    if (result == Filter.Result.ACCEPT) {
                        break;
                    }
                } catch (RuntimeException broken) {
                    // A broken mod filter must not stop logging.
                }
            }
        }
        for (Appender appender : appenders) {
            if (appender.isStarted()) {
                try {
                    appender.append(event);
                } catch (RuntimeException broken) {
                    // nor a broken appender
                }
            }
        }
        super.logMessage(fqcn, messageLevel, marker, message, t);
    }

    private static final class Event implements LogEvent {
        private final String loggerName;
        private final String fqcn;
        private final Level level;
        private final Marker marker;
        private final Message message;
        private final Throwable thrown;
        private final long millis = System.currentTimeMillis();
        private final String threadName = Thread.currentThread().getName();

        Event(String loggerName, String fqcn, Level level, Marker marker, Message message, Throwable thrown) {
            this.loggerName = loggerName;
            this.fqcn = fqcn;
            this.level = level;
            this.marker = marker;
            this.message = message;
            this.thrown = thrown;
        }

        @Override
        public Level getLevel() {
            return level;
        }

        @Override
        public String getLoggerName() {
            return loggerName;
        }

        @Override
        public Marker getMarker() {
            return marker;
        }

        @Override
        public Message getMessage() {
            return message;
        }

        @Override
        public long getMillis() {
            return millis;
        }

        @Override
        public String getThreadName() {
            return threadName;
        }

        @Override
        public Throwable getThrown() {
            return thrown;
        }

        @Override
        public String getFQCN() {
            return fqcn;
        }
    }
}
