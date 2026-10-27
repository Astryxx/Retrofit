package retrofit.legacy.boot;

/**
 * Whether a class's bytes contain any of a set of markers (names a transformation looks for), in one pass:
 * the markers are bucketed by their first two bytes, so most positions cost a table lookup. The transformers
 * ask this of every class loaded, which before took a scan per marker.
 */
final class ByteMarkers {
    /** First two bytes -> the markers starting with them (null: none). */
    private final byte[][][] byPrefix = new byte[1 << 16][][];
    /** Markers of a single byte, checked separately (none so far). */
    private final boolean[] single = new boolean[256];

    ByteMarkers(byte[][] markers) {
        for (byte[] marker : markers) {
            if (marker.length == 0) {
                continue;
            }
            if (marker.length == 1) {
                single[marker[0] & 0xFF] = true;
                continue;
            }
            int key = (marker[0] & 0xFF) << 8 | (marker[1] & 0xFF);
            byte[][] bucket = byPrefix[key];
            byte[][] grown = new byte[bucket == null ? 1 : bucket.length + 1][];
            if (bucket != null) {
                System.arraycopy(bucket, 0, grown, 0, bucket.length);
            }
            grown[grown.length - 1] = marker;
            byPrefix[key] = grown;
        }
    }

    boolean anyIn(byte[] bytes) {
        for (int i = 0, end = bytes.length - 1; i < end; i++) {
            if (single[bytes[i] & 0xFF]) {
                return true;
            }
            byte[][] candidates = byPrefix[(bytes[i] & 0xFF) << 8 | (bytes[i + 1] & 0xFF)];
            if (candidates == null) {
                continue;
            }
            for (byte[] marker : candidates) {
                if (matches(bytes, i, marker)) {
                    return true;
                }
            }
        }
        return bytes.length > 0 && single[bytes[bytes.length - 1] & 0xFF];
    }

    private static boolean matches(byte[] bytes, int at, byte[] marker) {
        if (at + marker.length > bytes.length) {
            return false;
        }
        for (int j = 2; j < marker.length; j++) {
            if (bytes[at + j] != marker[j]) {
                return false;
            }
        }
        return true;
    }
}
