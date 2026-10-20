package retrofit.legacy.compat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Replaces {@code URL.openStream()} and {@code URL.openConnection()} in legacy code (redirected by
 * RetrofitTransformer). Many 1.7.10 mods check for updates or fetch data from servers that have
 * long since gone, without timeouts, while the game loads; Java's defaults wait for minutes.
 * Connections get short timeouts (mods may still change them).
 *
 * Legacy code may not download other mods or code (CodeChicken's DepLoader would, without asking):
 * Retrofit asks the player about those before the game starts (the host's LegacyDependencies),
 * and anything else is refused with a message saying what to install by hand. Archives of data
 * (FiskHeroes' sound packs) still download: a .zip is refused only if it holds code.
 */
public final class LegacyNet {
    private static final int CONNECT_TIMEOUT = 3000;
    private static final int READ_TIMEOUT = 5000;

    private LegacyNet() {
    }

    public static URLConnection openConnection(URL url) throws IOException {
        String path = url.getPath().toLowerCase(java.util.Locale.ROOT);
        boolean remote = !"file".equals(url.getProtocol()) && !"jar".equals(url.getProtocol());
        if (remote && (path.endsWith(".jar") || path.endsWith(".class"))) {
            throw refused(url);
        }
        URLConnection connection = url.openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT);
        connection.setReadTimeout(READ_TIMEOUT);
        if (remote && path.endsWith(".zip") && connection instanceof HttpURLConnection) {
            return new CheckedArchive(url, (HttpURLConnection) connection);
        }
        return connection;
    }

    public static InputStream openStream(URL url) throws IOException {
        return openConnection(url).getInputStream();
    }

    private static IOException refused(URL url) {
        String path = url.getPath();
        String file = path.substring(path.lastIndexOf('/') + 1);
        System.out.println("[Retrofit] Refused a 1.7.10 mod's download of " + url);
        return new IOException("Retrofit does not let 1.7.10 mods download other mods: a mod wanted " + url
                + ". Download " + file + " from its mod's page yourself and put it in retrofit/mods.");
    }

    /** Whether an archive holds code: classes, jars, or a mod's description. */
    private static boolean holdsCode(byte[] archive) throws IOException {
        ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive));
        try {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                String name = entry.getName().toLowerCase(java.util.Locale.ROOT);
                if (name.endsWith(".class") || name.endsWith(".jar") || name.endsWith("mcmod.info") || name.endsWith("mods.toml")) {
                    return true;
                }
            }
        } finally {
            zip.close();
        }
        return false;
    }

    /**
     * An HTTP connection to a .zip whose body is read in full and looked into before legacy code
     * gets it. Everything else is the real connection's.
     */
    private static final class CheckedArchive extends HttpURLConnection {
        private final HttpURLConnection real;
        private byte[] body;

        CheckedArchive(URL url, HttpURLConnection real) {
            super(url);
            this.real = real;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            if (body == null) {
                InputStream in = real.getInputStream();
                try {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    for (int n; (n = in.read(buffer)) > 0; ) {
                        bytes.write(buffer, 0, n);
                    }
                    body = bytes.toByteArray();
                } finally {
                    in.close();
                }
                if (holdsCode(body)) {
                    body = null;
                    throw refused(url);
                }
            }
            return new ByteArrayInputStream(body);
        }

        @Override
        public void connect() throws IOException {
            real.connect();
            connected = true;
        }

        @Override
        public void disconnect() {
            real.disconnect();
        }

        @Override
        public boolean usingProxy() {
            return real.usingProxy();
        }

        @Override
        public int getResponseCode() throws IOException {
            return real.getResponseCode();
        }

        @Override
        public String getResponseMessage() throws IOException {
            return real.getResponseMessage();
        }

        @Override
        public void setRequestMethod(String method) throws java.net.ProtocolException {
            real.setRequestMethod(method);
        }

        @Override
        public String getRequestMethod() {
            return real.getRequestMethod();
        }

        @Override
        public void setRequestProperty(String key, String value) {
            real.setRequestProperty(key, value);
        }

        @Override
        public void addRequestProperty(String key, String value) {
            real.addRequestProperty(key, value);
        }

        @Override
        public String getRequestProperty(String key) {
            return real.getRequestProperty(key);
        }

        @Override
        public Map<String, List<String>> getRequestProperties() {
            return real.getRequestProperties();
        }

        @Override
        public void setConnectTimeout(int timeout) {
            real.setConnectTimeout(timeout);
        }

        @Override
        public int getConnectTimeout() {
            return real.getConnectTimeout();
        }

        @Override
        public void setReadTimeout(int timeout) {
            real.setReadTimeout(timeout);
        }

        @Override
        public int getReadTimeout() {
            return real.getReadTimeout();
        }

        @Override
        public void setInstanceFollowRedirects(boolean follow) {
            real.setInstanceFollowRedirects(follow);
        }

        @Override
        public boolean getInstanceFollowRedirects() {
            return real.getInstanceFollowRedirects();
        }

        @Override
        public void setUseCaches(boolean useCaches) {
            real.setUseCaches(useCaches);
        }

        @Override
        public void setDoOutput(boolean doOutput) {
            real.setDoOutput(doOutput);
        }

        @Override
        public void setDoInput(boolean doInput) {
            real.setDoInput(doInput);
        }

        @Override
        public OutputStream getOutputStream() throws IOException {
            return real.getOutputStream();
        }

        @Override
        public InputStream getErrorStream() {
            return real.getErrorStream();
        }

        @Override
        public int getContentLength() {
            return real.getContentLength();
        }

        @Override
        public long getContentLengthLong() {
            return real.getContentLengthLong();
        }

        @Override
        public String getContentType() {
            return real.getContentType();
        }

        @Override
        public long getLastModified() {
            return real.getLastModified();
        }

        @Override
        public String getHeaderField(String name) {
            return real.getHeaderField(name);
        }

        @Override
        public String getHeaderField(int n) {
            return real.getHeaderField(n);
        }

        @Override
        public String getHeaderFieldKey(int n) {
            return real.getHeaderFieldKey(n);
        }

        @Override
        public Map<String, List<String>> getHeaderFields() {
            return real.getHeaderFields();
        }
    }
}
