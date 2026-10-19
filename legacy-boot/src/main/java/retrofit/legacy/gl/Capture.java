package retrofit.legacy.gl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Vertices produced by emulated legacy drawing, for the host to submit with its own renderer.
 * Quads only; each vertex is {@link #FLOATS_PER_VERTEX} floats:
 * x y z (after the legacy modelview), u v, nx ny nz, r g b a.
 *
 * Per vertex there is also a {@link #state} (bound texture index | {@link #BLEND} if blending was
 * on | {@link #UNTEXTURED} if texturing was off) and a {@link #light} value (packed lightmap coordinates the legacy code set, or -1 to use the
 * host's light). Texture indexes refer to {@link #textureName}.
 *
 * Reset with {@link #begin()} before a legacy render call; read afterwards. The host serializes
 * all use of the emulation (it is single-threaded legacy state).
 */
public final class Capture {
    public static final int FLOATS_PER_VERTEX = 12;
    public static final int BLEND = 1 << 16;
    public static final int UNTEXTURED = 1 << 17;
    /** Blending added the colour (glBlendFunc(..., GL_ONE)): glows. */
    public static final int ADDITIVE = 1 << 18;
    /** Drawn with GL_LIGHTING off: no shading. */
    public static final int UNLIT = 1 << 19;
    /** A line segment a, b, as the quad a, b, b, a. */
    public static final int LINES = 1 << 20;
    public static final int TEXTURE_MASK = 0xFFFF;

    private static float[] data = new float[FLOATS_PER_VERTEX * 1024];
    private static int[] states = new int[1024];
    private static int[] lights = new int[1024];
    private static int vertexCount;
    /** Things the host draws itself, in drawing order: see {@link #mark}. */
    private static final List<Object[]> MARKERS = new ArrayList<Object[]>();

    private static final List<String> TEXTURES = new ArrayList<String>();
    private static final Map<String, Integer> TEXTURE_IDS = new HashMap<String, Integer>();

    private Capture() {
    }

    /** Clears captured vertices and resets the legacy GL state to its defaults. */
    public static void begin() {
        vertexCount = 0;
        MARKERS.clear();
        GL11.resetState();
    }

    /**
     * Records something for the host to draw at this point of the vertex stream (after the
     * vertices captured so far): {Integer vertex index, String kind, Object subject, float[] at}.
     */
    public static void mark(String kind, Object subject, float[] at) {
        MARKERS.add(new Object[]{vertexCount, kind, subject, at});
    }

    public static List<Object[]> markers() {
        return MARKERS;
    }

    public static float[] data() {
        return data;
    }

    public static int vertexCount() {
        return vertexCount;
    }

    /** Per-vertex states (see class doc); valid up to vertexCount(). */
    public static int[] states() {
        return states;
    }

    /** Per-vertex packed light, -1 for the host's; valid up to vertexCount(). */
    public static int[] lights() {
        return lights;
    }

    public static int state(int vertex) {
        return states[vertex];
    }

    public static int light(int vertex) {
        return lights[vertex];
    }

    /** The legacy ResourceLocation string of a texture index, e.g. "bibliocraft:textures/models/desk.png". */
    public static String textureName(int index) {
        return index < TEXTURES.size() ? TEXTURES.get(index) : "";
    }

    static int textureIndex(String name) {
        Integer index = TEXTURE_IDS.get(name);
        if (index == null) {
            index = TEXTURES.size();
            TEXTURES.add(name);
            TEXTURE_IDS.put(name, index);
        }
        return index;
    }

    static void emit(float x, float y, float z, float u, float v, float nx, float ny, float nz,
                     float r, float g, float b, float a, int state, int light) {
        int base = vertexCount * FLOATS_PER_VERTEX;
        if (base + FLOATS_PER_VERTEX > data.length) {
            float[] grown = new float[data.length * 2];
            System.arraycopy(data, 0, grown, 0, data.length);
            data = grown;
            int[] grownStates = new int[states.length * 2];
            System.arraycopy(states, 0, grownStates, 0, states.length);
            states = grownStates;
            int[] grownLights = new int[lights.length * 2];
            System.arraycopy(lights, 0, grownLights, 0, lights.length);
            lights = grownLights;
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
        // OpenGL clamps colours to [0, 1] (Thaumcraft's crystals ask for 1.16); 1.20.1's vertex bytes would wrap
        d[base + 8] = Math.max(0, Math.min(1, r));
        d[base + 9] = Math.max(0, Math.min(1, g));
        d[base + 10] = Math.max(0, Math.min(1, b));
        d[base + 11] = Math.max(0, Math.min(1, a));
        states[vertexCount] = state;
        lights[vertexCount] = light;
        vertexCount++;
    }
}
