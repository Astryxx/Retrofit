package retrofit.legacy.gl;

/**
 * Implemented subset of LWJGL 2's GL13 (see LwjglShims). OpenGlHelper.setLightmapTextureCoords
 * sets block/sky light with glMultiTexCoord2f on the lightmap unit; anything but texture unit 0
 * is taken as that.
 */
public final class GL13 {
    private static final int GL_TEXTURE0 = 0x84C0;

    private GL13() {
    }

    public static void glMultiTexCoord2f(int target, float s, float t) {
        if (target != GL_TEXTURE0) {
            GL11.setLightmap(s, t);
        }
    }
}
