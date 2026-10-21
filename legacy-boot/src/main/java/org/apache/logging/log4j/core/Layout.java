package org.apache.logging.log4j.core;

import java.io.Serializable;

/** log4j-core's layout as log4j 2.0-beta9 had it; see {@link Appender}. */
public interface Layout<T extends Serializable> {
    byte[] getFooter();

    byte[] getHeader();

    byte[] toByteArray(LogEvent event);

    T toSerializable(LogEvent event);

    String getContentType();
}
