package retrofit.legacy.gl;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Implemented subset of LWJGL 2's GL15 (see LwjglShims): buffer objects, kept as copies on this
 * side. Mods upload a model's arrays once (NTM's VBO meshes: positions, texture coordinates and
 * normals each in a buffer of its own) and later point at them by offset; the GL11 emulation
 * reads the arrays from here when they are drawn. Without them the pointers were ignored and the
 * draw read whatever arrays were set last.
 */
public final class GL15 {
    private static final int GL_ARRAY_BUFFER = 0x8892;
    private static final int GL_ELEMENT_ARRAY_BUFFER = 0x8893;
    private static final int GL_FLOAT = 0x1406;

    private static final Map<Integer, ByteBuffer> BUFFERS = new HashMap<Integer, ByteBuffer>();
    private static int nextId = 1;
    private static int arrayBuffer;
    private static int elementBuffer;

    private GL15() {
    }

    public static synchronized int glGenBuffers() {
        int id = nextId++;
        BUFFERS.put(id, ByteBuffer.allocate(0));
        return id;
    }

    public static void glGenBuffers(IntBuffer buffers) {
        for (int i = buffers.position(); i < buffers.limit(); i++) {
            buffers.put(i, glGenBuffers());
        }
    }

    public static synchronized void glDeleteBuffers(int buffer) {
        BUFFERS.remove(buffer);
        if (arrayBuffer == buffer) {
            arrayBuffer = 0;
        }
        if (elementBuffer == buffer) {
            elementBuffer = 0;
        }
    }

    public static void glDeleteBuffers(IntBuffer buffers) {
        for (int i = buffers.position(); i < buffers.limit(); i++) {
            glDeleteBuffers(buffers.get(i));
        }
    }

    public static synchronized boolean glIsBuffer(int buffer) {
        return BUFFERS.containsKey(buffer);
    }

    public static void glBindBuffer(int target, int buffer) {
        if (target == GL_ARRAY_BUFFER) {
            arrayBuffer = buffer;
        } else if (target == GL_ELEMENT_ARRAY_BUFFER) {
            elementBuffer = buffer;
        }
    }

    public static void glBufferData(int target, long size, int usage) {
        store(target, newBuffer((int) size));
    }

    public static void glBufferData(int target, ByteBuffer data, int usage) {
        ByteBuffer copy = newBuffer(data.remaining());
        copy.put(data.duplicate()).clear();
        store(target, copy);
    }

    public static void glBufferData(int target, FloatBuffer data, int usage) {
        ByteBuffer copy = newBuffer(data.remaining() * 4);
        copy.asFloatBuffer().put(data.duplicate());
        store(target, copy);
    }

    public static void glBufferData(int target, IntBuffer data, int usage) {
        ByteBuffer copy = newBuffer(data.remaining() * 4);
        copy.asIntBuffer().put(data.duplicate());
        store(target, copy);
    }

    public static void glBufferData(int target, ShortBuffer data, int usage) {
        ByteBuffer copy = newBuffer(data.remaining() * 2);
        copy.asShortBuffer().put(data.duplicate());
        store(target, copy);
    }

    public static void glBufferSubData(int target, long offset, ByteBuffer data) {
        ByteBuffer buffer = bound(target);
        if (buffer != null && offset + data.remaining() <= buffer.capacity()) {
            ByteBuffer at = buffer.duplicate();
            at.position((int) offset);
            at.put(data.duplicate());
        }
    }

    public static void glBufferSubData(int target, long offset, FloatBuffer data) {
        ByteBuffer buffer = bound(target);
        if (buffer != null && offset + data.remaining() * 4L <= buffer.capacity()) {
            ByteBuffer at = buffer.duplicate().order(buffer.order());
            at.position((int) offset);
            at.slice().order(buffer.order()).asFloatBuffer().put(data.duplicate());
        }
    }

    private static ByteBuffer newBuffer(int bytes) {
        return ByteBuffer.allocate(bytes).order(ByteOrder.nativeOrder());
    }

    private static synchronized void store(int target, ByteBuffer data) {
        int buffer = target == GL_ARRAY_BUFFER ? arrayBuffer : target == GL_ELEMENT_ARRAY_BUFFER ? elementBuffer : 0;
        if (buffer != 0) {
            BUFFERS.put(buffer, data);
        }
    }

    private static synchronized ByteBuffer bound(int target) {
        return BUFFERS.get(target == GL_ARRAY_BUFFER ? arrayBuffer : target == GL_ELEMENT_ARRAY_BUFFER ? elementBuffer : 0);
    }

    /** The bound array buffer from a byte offset on, or null when none is bound (the pointer is then a client address GL11 can't follow). */
    static ByteBuffer arrayBytes(long offset) {
        ByteBuffer buffer = arrayBuffer == 0 ? null : bound(GL_ARRAY_BUFFER);
        if (buffer == null || offset < 0 || offset > buffer.capacity()) {
            return null;
        }
        ByteBuffer at = buffer.duplicate();
        at.position((int) offset);
        return at.slice().order(buffer.order());
    }

    /** The bound array buffer as floats from a byte offset on, for a pointer of that type; null otherwise. */
    static FloatBuffer arrayFloats(int type, long offset) {
        ByteBuffer bytes = type == GL_FLOAT ? arrayBytes(offset) : null;
        return bytes == null ? null : bytes.asFloatBuffer();
    }
}
