package retrofit.legacy.compat;

import java.io.IOException;
import java.io.InputStream;
import java.util.function.BiConsumer;
import java.util.jar.JarOutputStream;

/**
 * Stand-in for {@code java.util.jar.Pack200}, which was removed in Java 14.
 * Forge 1.7.10 uses it to unpack its binary patches. References to the real
 * class are rewritten to this one by {@link retrofit.legacy.boot.RetrofitTransformer}.
 *
 * The host (harness or the 1.20.1 mod) supplies the implementation, because a
 * modern Pack200 decoder can't live on the 1.7.10 classpath without clashing
 * with vanilla's old commons-compress.
 */
public final class Pack200 {
    public static volatile BiConsumer<InputStream, JarOutputStream> impl;

    private Pack200() {
    }

    public static Unpacker newUnpacker() {
        return (in, out) -> {
            BiConsumer<InputStream, JarOutputStream> unpacker = impl;
            if (unpacker == null) {
                throw new IOException("Retrofit: no Pack200 implementation was provided by the host");
            }
            unpacker.accept(in, out);
            // The JDK unpacker always finished the jar; FML reads the bytes without closing the stream.
            // Some commons-compress versions close it instead, which already finished it.
            try {
                out.finish();
            } catch (IOException alreadyClosed) {
                // Closing implies finishing.
            }
        };
    }

    public interface Unpacker {
        void unpack(InputStream in, JarOutputStream out) throws IOException;
    }
}
