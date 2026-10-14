package retrofit.legacy.gl;

import java.nio.IntBuffer;

/** Implemented subset of LWJGL 2's ARBShaderObjects (see LwjglShims): see {@link Shaders}. */
public final class ARBShaderObjects {
    private ARBShaderObjects() {
    }

    public static int glCreateProgramObjectARB() {
        return Shaders.create();
    }

    public static int glCreateShaderObjectARB(int type) {
        return Shaders.create();
    }

    public static int glGetObjectParameteriARB(int object, int name) {
        return Shaders.parameter(name);
    }

    public static void glGetObjectParameterARB(int object, int name, IntBuffer params) {
        params.put(params.position(), Shaders.parameter(name));
    }

    public static String glGetInfoLogARB(int object, int maxLength) {
        return "";
    }

    public static int glGetUniformLocationARB(int program, CharSequence name) {
        return Shaders.location();
    }
}
