package retrofit.legacy.compat;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;

/**
 * Replaces {@code URL.openStream()} and {@code URL.openConnection()} in legacy code (redirected by
 * RetrofitTransformer). Many 1.7.10 mods check for updates or fetch data from servers that have
 * long since gone, without timeouts, while the game loads; Java's defaults wait for minutes.
 * Connections get short timeouts (mods may still change them).
 */
public final class LegacyNet {
    private static final int CONNECT_TIMEOUT = 3000;
    private static final int READ_TIMEOUT = 5000;

    private LegacyNet() {
    }

    public static URLConnection openConnection(URL url) throws IOException {
        URLConnection connection = url.openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT);
        connection.setReadTimeout(READ_TIMEOUT);
        return connection;
    }

    public static InputStream openStream(URL url) throws IOException {
        return openConnection(url).getInputStream();
    }
}
