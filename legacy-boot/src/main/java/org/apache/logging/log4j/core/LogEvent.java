package org.apache.logging.log4j.core;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.message.Message;

/** The part of log4j-core's log event that filters read; see {@link Logger}. */
public interface LogEvent {
    Level getLevel();

    String getLoggerName();

    Marker getMarker();

    Message getMessage();

    long getMillis();

    String getThreadName();

    Throwable getThrown();

    String getFQCN();
}
