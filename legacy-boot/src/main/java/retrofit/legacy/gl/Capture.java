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
 * on) and a {@link #light} value (packed lightmap coordinates the legacy code set, or -1 to use the
 * host's light). Texture indexes refer to {@link #textureName}.
 *
 * Reset with {@link #begin()} before a legacy render call; read afterwards. The host serializes
 * all use of the emulation (it is single-threaded legacy state).
 */
public final class Capture {
    public static final int FLOATS_PER_VERTEX = 12;
    public static final int BLEND = 1 << 16;
    public static final int TEXTURE_MASK = 0xFFFF;

    private static float[] data = new float[FLOATS_PER_VERTEX * 1024];
    private static int[] states = new int[1024];
    private static int[] lights = new int[1024];
    private static int vertexCount;

    private static final List<String> TEXTURES = new ArrayList<String>();
    private static final Map<String, Integer> TEXTURE_IDS = new HashMap<String, Integer>();

    private Capture() {
    }

    /** Clears captured vertices and resets the legacy GL state to its defaults. */
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
        d[base + 8] = r;
        d[base + 9] = g;
        d[base + 10] = b;
        d[base + 11] = a;
        states[vertexCount] = state;
        lights[vertexCount] = light;
        vertexCount++;
    }
}
