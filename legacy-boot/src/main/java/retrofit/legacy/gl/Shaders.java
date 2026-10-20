package retrofit.legacy.gl;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shader objects for GL20 and ARBShaderObjects. Legacy shaders cannot run: the GL emulation
 * captures geometry for the host's own render types. Shader calls succeed (programs and shaders
 * get ids, compile and link, uniforms have locations) so mods take the path every modern GPU
 * takes, and what they draw with a program bound comes out unshaded.
 */
final class Shaders {
    private static final AtomicInteger IDS = new AtomicInteger(1);
    private static final AtomicInteger UNIFORMS = new AtomicInteger(0);
    /** COMPILE_STATUS, LINK_STATUS, VALIDATE_STATUS (and their ARB equivalents). */
    private static final int COMPILE_STATUS = 0x8B81;
    private static final int LINK_STATUS = 0x8B82;
    private static final int VALIDATE_STATUS = 0x8B83;

    private Shaders() {
    }

    static int create() {
        return IDS.getAndIncrement();
    }

    static int parameter(int name) {
        return name == COMPILE_STATUS || name == LINK_STATUS || name == VALIDATE_STATUS ? 1 : 0;
    }

    static int location() {
        return UNIFORMS.getAndIncrement();
    }
}
