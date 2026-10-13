package retrofit.legacy.gl;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Emulation of LWJGL 2's {@code org.lwjgl.opengl.GL11} for legacy code; references to the
 * real class are rewritten to this one. Instead of talking to a GL context it tracks the
 * fixed-function state that matters for geometry (modelview stack, current colour, display
 * lists, client-side vertex arrays) and turns draws into {@link Capture}d quads that the host
 * renders with its own pipeline. That keeps legacy rendering independent of the host's GL
 * profile (1.20.1 uses a core profile, macOS has no compatibility profile at all).
 *
 * Method names and descriptors must match LWJGL 2 exactly. Only what legacy model rendering
 * needs is implemented; LWJGL methods without an implementation here are generated as no-ops
 * (see LwjglShims). Besides geometry it tracks the state that decides how captured quads are
 * drawn: the bound texture (set by the legacy TextureManager), blending, and lightmap
 * coordinates. Only the modelview matrix is applied; projection and texture matrices are the
 * host's. The host serializes all calls.
 */
public final class GL11 {
    private static final int GL_COMPILE_AND_EXECUTE = 0x1301;
    private static final int GL_TRIANGLES = 0x0004;
    private static final int GL_QUADS = 0x0007;
    private static final int GL_VERTEX_ARRAY = 0x8074;
    private static final int GL_NORMAL_ARRAY = 0x8075;
    private static final int GL_COLOR_ARRAY = 0x8076;
    private static final int GL_TEXTURE_COORD_ARRAY = 0x8078;
    private static final int GL_MODELVIEW = 0x1700;
    private static final int GL_BLEND = 0x0BE2;
    private static final int GL_VENDOR = 0x1F00;
    private static final int GL_RENDERER = 0x1F01;
    private static final int GL_VERSION = 0x1F02;
    private static final int GL_MAX_TEXTURE_SIZE = 0x0D33;

    private static final List<Matrix4> stack = new ArrayList<Matrix4>();
    private static Matrix4 modelview = new Matrix4();
    private static float red = 1, green = 1, blue = 1, alpha = 1;
    private static int matrixMode = GL_MODELVIEW;
    private static int texture;
    private static boolean blend;
    /** Packed lightmap coordinates set by legacy code, or -1 for the host's light. */
    private static int light = -1;
    private static final List<float[]> attribStack = new ArrayList<float[]>();

    private static final Map<Integer, List<Op>> lists = new HashMap<Integer, List<Op>>();
    private static int nextList = 1;
    private static List<Op> recording;
    private static boolean executeWhileRecording;

    private static boolean vertexArray, normalArray, colorArray, texCoordArray;
    private static FloatBuffer vertexPointer, texCoordPointer;
    private static ByteBuffer colorPointer, normalPointer;
    private static int vertexBase, vertexStride, vertexSize, texCoordBase, texCoordStride;
    private static int colorBase, colorStride, normalBase, normalStride;

    private GL11() {
    }

    /** A recorded display-list command. */
    private interface Op {
        void run();
    }

    static void resetState() {
        stack.clear();
        modelview = new Matrix4();
        red = green = blue = alpha = 1;
        matrixMode = GL_MODELVIEW;
        blend = false;
        light = -1;
        attribStack.clear();
    }

    /** Called when the legacy TextureManager binds a texture (see Hooks). */
    public static void bindLegacyTexture(String location) {
        final int index = Capture.textureIndex(location);
        submit(new Op() {
            public void run() {
                texture = index;
            }
        });
    }

    /** Lightmap coordinates from glMultiTexCoord2f on the lightmap unit (OpenGlHelper.setLightmapTextureCoords). */
    static void setLightmap(float u, float v) {
        final int packed = ((int) v & 0xFFFF) << 16 | ((int) u & 0xFFFF);
        submit(new Op() {
            public void run() {
                light = packed;
            }
        });
    }

    /**
     * Records the command into the open display list, and runs it unless only compiling.
     * Hot paths skip this (and the Op allocation) when no list is being recorded.
     */
    private static void submit(Op op) {
        if (recording != null) {
            recording.add(op);
            if (!executeWhileRecording) {
                return;
            }
        }
        op.run();
    }

    // ---- Matrix stack ----

    public static void glMatrixMode(final int mode) {
        submit(new Op() {
            public void run() {
                matrixMode = mode;
            }
        });
    }

    public static void glPushMatrix() {
        if (recording == null) { // common case: no allocation
            if (matrixMode == GL_MODELVIEW) {
                stack.add(modelview.copy());
            }
            return;
        }
        submit(new Op() {
            public void run() {
                if (matrixMode == GL_MODELVIEW) {
                stack.add(modelview.copy());
            }
            }
        });
    }

    public static void glPopMatrix() {
        if (recording == null) { // common case: no allocation
            if (matrixMode == GL_MODELVIEW && !stack.isEmpty()) {
                modelview = stack.remove(stack.size() - 1);
            }
            return;
        }
        submit(new Op() {
            public void run() {
                if (matrixMode == GL_MODELVIEW && !stack.isEmpty()) {
                    modelview = stack.remove(stack.size() - 1);
                }
            }
        });
    }

    public static void glLoadIdentity() {
        submit(new Op() {
            public void run() {
                if (matrixMode == GL_MODELVIEW) {
                modelview.identity();
            }
            }
        });
    }

    public static void glTranslatef(final float x, final float y, final float z) {
        if (recording == null) { // common case: no allocation
            if (matrixMode == GL_MODELVIEW) {
                modelview.translate(x, y, z);
            }
            return;
        }
        submit(new Op() {
            public void run() {
                if (matrixMode == GL_MODELVIEW) {
                modelview.translate(x, y, z);
            }
            }
        });
    }

    public static void glTranslated(double x, double y, double z) {
        glTranslatef((float) x, (float) y, (float) z);
    }

    public static void glRotatef(final float angle, final float x, final float y, final float z) {
        if (recording == null) { // common case: no allocation
            if (matrixMode == GL_MODELVIEW) {
                modelview.rotate(angle, x, y, z);
            }
            return;
        }
        submit(new Op() {
            public void run() {
                if (matrixMode == GL_MODELVIEW) {
                modelview.rotate(angle, x, y, z);
            }
            }
        });
    }

    public static void glScalef(final float x, final float y, final float z) {
        if (recording == null) { // common case: no allocation
            if (matrixMode == GL_MODELVIEW) {
                modelview.scale(x, y, z);
            }
            return;
        }
        submit(new Op() {
            public void run() {
                if (matrixMode == GL_MODELVIEW) {
                modelview.scale(x, y, z);
            }
            }
        });
    }

    public static void glScaled(double x, double y, double z) {
        glScalef((float) x, (float) y, (float) z);
    }

    public static void glMultMatrix(FloatBuffer matrix) {
        final float[] values = new float[16];
        int base = matrix.position();
        for (int i = 0; i < 16; i++) {
            values[i] = matrix.get(base + i);
        }
        submit(new Op() {
            public void run() {
                if (matrixMode == GL_MODELVIEW) {
                modelview.multiply(values);
            }
            }
        });
    }

    // ---- Current colour ----

    public static void glColor4f(final float r, final float g, final float b, final float a) {
        if (recording == null) { // common case: no allocation
            red = r;
            green = g;
            blue = b;
            alpha = a;
            return;
        }
        submit(new Op() {
            public void run() {
                red = r;
                green = g;
                blue = b;
                alpha = a;
            }
        });
    }

    public static void glColor3f(float r, float g, float b) {
        glColor4f(r, g, b, 1);
    }

    // ---- Display lists ----

    public static int glGenLists(int range) {
        int first = nextList;
        nextList += range;
        return first;
    }

    public static void glDeleteLists(int list, int range) {
        for (int i = 0; i < range; i++) {
            lists.remove(list + i);
        }
    }

    public static void glNewList(int list, int mode) {
        recording = new ArrayList<Op>();
        executeWhileRecording = mode == GL_COMPILE_AND_EXECUTE;
        lists.put(list, recording);
    }

    public static void glEndList() {
        recording = null;
    }

    public static void glCallList(final int list) {
        submit(new Op() {
            public void run() {
                List<Op> ops = lists.get(list);
                if (ops != null) {
                    for (Op op : ops) {
                        op.run();
                    }
                }
            }
        });
    }

    // ---- Client-side vertex arrays (what Tessellator.draw uses) ----

    public static void glEnableClientState(int array) {
        setClientState(array, true);
    }

    public static void glDisableClientState(int array) {
        setClientState(array, false);
    }

    private static void setClientState(int array, boolean enabled) {
        switch (array) {
            case GL_VERTEX_ARRAY: vertexArray = enabled; break;
            case GL_NORMAL_ARRAY: normalArray = enabled; break;
            case GL_COLOR_ARRAY: colorArray = enabled; break;
            case GL_TEXTURE_COORD_ARRAY: texCoordArray = enabled; break;
            default: break;
        }
    }

    public static void glVertexPointer(int size, int stride, FloatBuffer pointer) {
        vertexPointer = pointer;
        vertexBase = pointer.position();
        vertexSize = size;
        vertexStride = stride == 0 ? size : stride / 4;
    }

    public static void glTexCoordPointer(int size, int stride, FloatBuffer pointer) {
        texCoordPointer = pointer;
        texCoordBase = pointer.position();
        texCoordStride = stride == 0 ? size : stride / 4;
    }

    /** Lightmap coordinates; the host supplies its own lighting. */
    public static void glTexCoordPointer(int size, int stride, ShortBuffer pointer) {
    }

    public static void glColorPointer(int size, boolean unsigned, int stride, ByteBuffer pointer) {
        colorPointer = pointer;
        colorBase = pointer.position();
        colorStride = stride == 0 ? size : stride;
    }

    public static void glNormalPointer(int stride, ByteBuffer pointer) {
        normalPointer = pointer;
        normalBase = pointer.position();
        normalStride = stride == 0 ? 3 : stride;
    }

    public static void glDrawArrays(int mode, int first, int count) {
        if (!vertexArray || (mode != GL_QUADS && mode != GL_TRIANGLES)) {
            return;
        }
        // Copy the vertices now: the arrays are reused by the caller as soon as this returns.
        final float[] local = new float[count * Capture.FLOATS_PER_VERTEX];
        for (int i = 0; i < count; i++) {
            int v = first + i;
            int o = i * Capture.FLOATS_PER_VERTEX;
            int p = vertexBase + v * vertexStride;
            local[o] = vertexPointer.get(p);
            local[o + 1] = vertexPointer.get(p + 1);
            local[o + 2] = vertexSize > 2 ? vertexPointer.get(p + 2) : 0;
            if (texCoordArray && texCoordPointer != null) {
                int t = texCoordBase + v * texCoordStride;
                local[o + 3] = texCoordPointer.get(t);
                local[o + 4] = texCoordPointer.get(t + 1);
            }
            if (normalArray && normalPointer != null) {
                int n = normalBase + v * normalStride;
                local[o + 5] = normalPointer.get(n) / 127f;
                local[o + 6] = normalPointer.get(n + 1) / 127f;
                local[o + 7] = normalPointer.get(n + 2) / 127f;
            } else {
                local[o + 6] = 1;
            }
            if (colorArray && colorPointer != null) {
                int c = colorBase + v * colorStride;
                for (int k = 0; k < 4; k++) {
                    local[o + 8 + k] = (colorPointer.get(c + k) & 0xFF) / 255f;
                }
            } else {
                local[o + 8] = Float.NaN; // use the current colour at execution time
            }
        }
        final boolean triangles = mode == GL_TRIANGLES;
        submit(new Op() {
            public void run() {
                emit(local, triangles);
            }
        });
    }

    private static void emit(float[] local, boolean triangles) {
        int count = local.length / Capture.FLOATS_PER_VERTEX;
        int perPrimitive = triangles ? 3 : 4;
        for (int start = 0; start + perPrimitive <= count; start += perPrimitive) {
            for (int corner = 0; corner < 4; corner++) {
                // Triangles become quads with a repeated last vertex.
                int v = start + Math.min(corner, perPrimitive - 1);
                emitVertex(local, v * Capture.FLOATS_PER_VERTEX);
            }
        }
    }

    private static void emitVertex(float[] d, int o) {
        float[] m = modelview.m;
        float x = d[o], y = d[o + 1], z = d[o + 2];
        float nx = d[o + 5], ny = d[o + 6], nz = d[o + 7];
        float tx = m[0] * nx + m[4] * ny + m[8] * nz;
        float ty = m[1] * nx + m[5] * ny + m[9] * nz;
        float tz = m[2] * nx + m[6] * ny + m[10] * nz;
        float length = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
        if (length > 0) {
            tx /= length;
            ty /= length;
            tz /= length;
        }
        boolean currentColour = Float.isNaN(d[o + 8]);
        Capture.emit(
                m[0] * x + m[4] * y + m[8] * z + m[12],
                m[1] * x + m[5] * y + m[9] * z + m[13],
                m[2] * x + m[6] * y + m[10] * z + m[14],
                d[o + 3], d[o + 4], tx, ty, tz,
                currentColour ? red : d[o + 8] * red,
                currentColour ? green : d[o + 9] * green,
                currentColour ? blue : d[o + 10] * blue,
                currentColour ? alpha : d[o + 11] * alpha,
                texture | (blend ? Capture.BLEND : 0), light);
    }

    // ---- Immediate mode (glBegin/glEnd), used by FontRenderer and many mods ----

    private static final int GL_TRIANGLE_STRIP = 0x0005;
    private static final int GL_TRIANGLE_FAN = 0x0006;
    private static final int GL_QUAD_STRIP = 0x0008;
    private static final int GL_POLYGON = 0x0009;

    private static int immediateMode = -1;
    private static float[] immediate = new float[Capture.FLOATS_PER_VERTEX * 64];
    private static int immediateCount;
    private static float texU, texV;
    private static float normalX, normalY = 1, normalZ;

    public static void glBegin(int mode) {
        immediateMode = mode;
        immediateCount = 0;
    }

    public static void glTexCoord2f(float u, float v) {
        texU = u;
        texV = v;
    }

    public static void glTexCoord2d(double u, double v) {
        glTexCoord2f((float) u, (float) v);
    }

    public static void glNormal3f(float nx, float ny, float nz) {
        normalX = nx;
        normalY = ny;
        normalZ = nz;
    }

    public static void glVertex3f(float x, float y, float z) {
        if (immediateMode < 0) {
            return;
        }
        int o = immediateCount * Capture.FLOATS_PER_VERTEX;
        if (o + Capture.FLOATS_PER_VERTEX > immediate.length) {
            float[] grown = new float[immediate.length * 2];
            System.arraycopy(immediate, 0, grown, 0, immediate.length);
            immediate = grown;
        }
        float[] d = immediate;
        d[o] = x;
        d[o + 1] = y;
        d[o + 2] = z;
        d[o + 3] = texU;
        d[o + 4] = texV;
        d[o + 5] = normalX;
        d[o + 6] = normalY;
        d[o + 7] = normalZ;
        d[o + 8] = red;
        d[o + 9] = green;
        d[o + 10] = blue;
        d[o + 11] = alpha;
        immediateCount++;
    }

    public static void glVertex3d(double x, double y, double z) {
        glVertex3f((float) x, (float) y, (float) z);
    }

    public static void glVertex2f(float x, float y) {
        glVertex3f(x, y, 0);
    }

    public static void glVertex2d(double x, double y) {
        glVertex3f((float) x, (float) y, 0);
    }

    public static void glVertex2i(int x, int y) {
        glVertex3f(x, y, 0);
    }

    public static void glVertex3i(int x, int y, int z) {
        glVertex3f(x, y, z);
    }

    /** Converts what was drawn since glBegin into quads (strips and fans included); lines and points are skipped. */
    public static void glEnd() {
        int mode = immediateMode;
        immediateMode = -1;
        int n = immediateCount;
        int[] order;
        if (mode == GL_QUADS) {
            order = sequence(n - n % 4);
        } else if (mode == GL_TRIANGLES) {
            order = trianglesAsQuads(sequence(n - n % 3));
        } else if (mode == GL_TRIANGLE_STRIP || mode == GL_QUAD_STRIP) {
            int quads = Math.max(0, (n - 2) / 2);
            order = new int[quads * 4];
            for (int q = 0; q < quads; q++) {
                int i = q * 2;
                order[q * 4] = i;
                order[q * 4 + 1] = i + 1;
                order[q * 4 + 2] = i + 3;
                order[q * 4 + 3] = i + 2;
            }
        } else if (mode == GL_TRIANGLE_FAN || mode == GL_POLYGON) {
            int triangles = Math.max(0, n - 2);
            int[] fan = new int[triangles * 3];
            for (int t = 0; t < triangles; t++) {
                fan[t * 3] = 0;
                fan[t * 3 + 1] = t + 1;
                fan[t * 3 + 2] = t + 2;
            }
            order = trianglesAsQuads(fan);
        } else {
            return;
        }
        final float[] local = new float[order.length * Capture.FLOATS_PER_VERTEX];
        for (int i = 0; i < order.length; i++) {
            System.arraycopy(immediate, order[i] * Capture.FLOATS_PER_VERTEX, local, i * Capture.FLOATS_PER_VERTEX, Capture.FLOATS_PER_VERTEX);
        }
        submit(new Op() {
            public void run() {
                emit(local, false);
            }
        });
    }

    private static int[] sequence(int n) {
        int[] order = new int[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        return order;
    }

    /** Triangle vertex order -> quads with the last vertex repeated. */
    private static int[] trianglesAsQuads(int[] triangles) {
        int[] quads = new int[triangles.length / 3 * 4];
        for (int t = 0; t < triangles.length / 3; t++) {
            quads[t * 4] = triangles[t * 3];
            quads[t * 4 + 1] = triangles[t * 3 + 1];
            quads[t * 4 + 2] = triangles[t * 3 + 2];
            quads[t * 4 + 3] = triangles[t * 3 + 2];
        }
        return quads;
    }

    // ---- State the host owns: accepted and ignored ----

    public static void glEnable(int cap) {
        setCapability(cap, true);
    }

    public static void glDisable(int cap) {
        setCapability(cap, false);
    }

    private static void setCapability(int cap, final boolean enabled) {
        if (cap == GL_BLEND) {
            submit(new Op() {
                public void run() {
                    blend = enabled;
                }
            });
        }
    }

    public static boolean glIsEnabled(int cap) {
        return cap == GL_BLEND && blend;
    }

    public static void glPushAttrib(int mask) {
        submit(new Op() {
            public void run() {
                attribStack.add(new float[]{red, green, blue, alpha, blend ? 1 : 0, texture, light});
            }
        });
    }

    public static void glPopAttrib() {
        submit(new Op() {
            public void run() {
                if (!attribStack.isEmpty()) {
                    float[] saved = attribStack.remove(attribStack.size() - 1);
                    red = saved[0];
                    green = saved[1];
                    blue = saved[2];
                    alpha = saved[3];
                    blend = saved[4] != 0;
                    texture = (int) saved[5];
                    light = (int) saved[6];
                }
            }
        });
    }

    public static String glGetString(int name) {
        switch (name) {
            case GL_VENDOR: return "Retrofit";
            case GL_RENDERER: return "Retrofit legacy GL emulation";
            case GL_VERSION: return "2.1 Retrofit";
            default: return "";
        }
    }

    public static int glGetInteger(int name) {
        return name == GL_MAX_TEXTURE_SIZE ? 16384 : 0;
    }

    public static int glGetError() {
        return 0;
    }

    public static void glColor4ub(byte r, byte g, byte b, byte a) {
        glColor4f((r & 0xFF) / 255f, (g & 0xFF) / 255f, (b & 0xFF) / 255f, (a & 0xFF) / 255f);
    }

    public static void glColor3ub(byte r, byte g, byte b) {
        glColor4ub(r, g, b, (byte) 255);
    }

    public static void glBlendFunc(int sfactor, int dfactor) {
    }

    public static void glAlphaFunc(int func, float ref) {
    }

    public static void glDepthMask(boolean flag) {
    }

    public static void glShadeModel(int mode) {
    }

    public static void glCullFace(int mode) {
    }

}
