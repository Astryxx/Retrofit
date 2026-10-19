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
 *
 * Legacy code may not download other mods or code (CodeChicken's DepLoader would, without asking):
 * Retrofit asks the player about those before the game starts (the host's LegacyDependencies),
 * and anything else is refused with a message saying what to install by hand.
 */
public final class LegacyNet {
    private static final int CONNECT_TIMEOUT = 3000;
    private static final int READ_TIMEOUT = 5000;

    private LegacyNet() {
    }

    public static URLConnection openConnection(URL url) throws IOException {
        String path = url.getPath().toLowerCase(java.util.Locale.ROOT);
        if (!"file".equals(url.getProtocol()) && !"jar".equals(url.getProtocol())
                && (path.endsWith(".jar") || path.endsWith(".zip") || path.endsWith(".class"))) {
            String file = path.substring(path.lastIndexOf('/') + 1);
            System.out.println("[Retrofit] Refused a 1.7.10 mod's download of " + url);
            throw new IOException("Retrofit does not let 1.7.10 mods download other mods: a mod wanted " + url
                    + ". Download " + file + " from its mod's page yourself and put it in retrofit/mods.");
        }
        URLConnection connection = url.openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT);
        connection.setReadTimeout(READ_TIMEOUT);
        return connection;
    }

    public static InputStream openStream(URL url) throws IOException {
        return openConnection(url).getInputStream();
    }
}
