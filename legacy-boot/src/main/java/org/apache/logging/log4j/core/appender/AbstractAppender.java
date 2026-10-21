package org.apache.logging.log4j.core.appender;

import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;

import java.io.Serializable;

/** log4j-core's base appender as log4j 2.0-beta9 had it; see {@link Appender}. */
public abstract class AbstractAppender implements Appender {
    private final String name;
    private final Filter filter;
    private final Layout<? extends Serializable> layout;
    private final boolean ignoreExceptions;
    private volatile boolean started;

    protected AbstractAppender(String name, Filter filter, Layout<? extends Serializable> layout) {
        this(name, filter, layout, true);
    }

    protected AbstractAppender(String name, Filter filter, Layout<? extends Serializable> layout, boolean ignoreExceptions) {
        this.name = name;
        this.filter = filter;
        this.layout = layout;
        this.ignoreExceptions = ignoreExceptions;
    }

    @Override
    public String getName() {
        return name;
    }

    public Filter getFilter() {
        return filter;
    }

    @Override
    public Layout<? extends Serializable> getLayout() {
        return layout;
    }

    @Override
    public boolean isIgnoreExceptions() {
        return ignoreExceptions;
    }

    @Override
    public void start() {
        started = true;
    }

    @Override
    public void stop() {
        started = false;
    }

    @Override
    public boolean isStarted() {
        return started;
    }

    @Override
    public String toString() {
        return name;
    }
}
