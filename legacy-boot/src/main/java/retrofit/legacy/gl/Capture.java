package retrofit.legacy.gl;

/**
 * Vertices produced by emulated legacy drawing, for the host to submit with its own renderer.
 * Quads only; each vertex is {@link #FLOATS_PER_VERTEX} floats:
 * x y z (after the legacy modelview), u v, nx ny nz, r g b a.
 *
 * Reset with {@link #begin()} before a legacy render call; read with {@link #data()} and
 * {@link #vertexCount()} afterwards. Render thread only.
 */
public final class Capture {
    public static final int FLOATS_PER_VERTEX = 12;

    private static float[] data = new float[FLOATS_PER_VERTEX * 1024];
    private static int vertexCount;

    private Capture() {
    }

    /** Clears captured vertices and resets the legacy matrix stack to identity. */
    public static void begin() {
        vertexCount = 0;
        GL11.resetState();
    }

    public static float[] data() {
        return data;
    }

    public static int vertexCount() {
        return vertexCount;
    }

    static void emit(float x, float y, float z, float u, float v, float nx, float ny, float nz,
                     float r, float g, float b, float a) {
        int base = vertexCount * FLOATS_PER_VERTEX;
        if (base + FLOATS_PER_VERTEX > data.length) {
            float[] grown = new float[data.length * 2];
            System.arraycopy(data, 0, grown, 0, data.length);
            data = grown;
        }
        float[] d = data;
        d[base] = x;
        d[base + 1] = y;
        d[base + 2] = z;
        d[base + 3] = u;
        d[base + 4] = v;
        d[base + 5] = nx;
        d[base + 6] = ny;
        d[base + 7] = nz;
        d[base + 8] = r;
        d[base + 9] = g;
        d[base + 10] = b;
        d[base + 11] = a;
        vertexCount++;
    }
}
