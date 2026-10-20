package retrofit.legacy.gl;

/** Implemented subset of LWJGL 2's ARBMultitexture: the ARB route OpenGlHelper takes for lightmap coordinates. */
public final class ARBMultitexture {
    private ARBMultitexture() {
    }

    public static void glMultiTexCoord2fARB(int target, float s, float t) {
        GL13.glMultiTexCoord2f(target, s, t);
    }
}
