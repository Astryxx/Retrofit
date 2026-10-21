package org.apache.logging.log4j.core;

import java.io.Serializable;

/**
 * log4j-core's appender as Forge 1.7.10's log4j 2.0-beta9 had it, for mods that watch log output
 * (Mixin waits for FML's "Validating minecraft" to enter its init phase). Appenders added to a
 * {@link Logger} get its events; output itself stays the host's. See {@link Logger}.
 */
public interface Appender {
    void append(LogEvent event);

    String getName();

    Layout<? extends Serializable> getLayout();

    boolean isIgnoreExceptions();

    void start();

    void stop();

    boolean isStarted();
}
