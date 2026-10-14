package retrofit.legacy.gl;

import java.nio.IntBuffer;

/** Implemented subset of LWJGL 2's GL20 (see LwjglShims): shader objects, see {@link Shaders}. */
public final class GL20 {
    private GL20() {
    }

    public static int glCreateProgram() {
        return Shaders.create();
    }

    public static int glCreateShader(int type) {
        return Shaders.create();
    }

    public static int glGetShaderi(int shader, int name) {
        return Shaders.parameter(name);
    }

    public static int glGetShader(int shader, int name) {
        return Shaders.parameter(name);
    }

    public static void glGetShader(int shader, int name, IntBuffer params) {
        params.put(params.position(), Shaders.parameter(name));
    }

    public static int glGetProgrami(int program, int name) {
        return Shaders.parameter(name);
    }

    public static int glGetProgram(int program, int name) {
        return Shaders.parameter(name);
    }

    public static void glGetProgram(int program, int name, IntBuffer params) {
        params.put(params.position(), Shaders.parameter(name));
    }

    public static String glGetShaderInfoLog(int shader, int maxLength) {
        return "";
    }

    public static String glGetProgramInfoLog(int program, int maxLength) {
        return "";
    }

    public static int glGetUniformLocation(int program, CharSequence name) {
        return Shaders.location();
    }

    public static int glGetAttribLocation(int program, CharSequence name) {
        return Shaders.location();
    }
}
