package retrofit.legacy.bridge;

import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.util.ResourceLocation;
import retrofit.legacy.gl.GL11;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * Client only. The legacy TextureManager's loadTexture (redirected by HeadlessTransformer): the
 * host draws every texture, so nothing is uploaded. The texture object is registered (getTexture
 * finds it) under a GL id that, bound by legacy code, selects that texture in the GL emulation.
 */
public final class ClientTextures {
    private static Field textures;
    private static Field glId;

    private ClientTextures() {
    }

    @SuppressWarnings("unchecked")
    public static boolean loadTexture(TextureManager manager, ResourceLocation location, ITextureObject texture) {
        try {
            if (textures == null) {
                textures = TextureManager.class.getDeclaredField("field_110585_a"); // mapTextureObjects
                textures.setAccessible(true);
                glId = AbstractTexture.class.getDeclaredField("field_110553_a"); // glTextureId
                glId.setAccessible(true);
            }
            ((Map<Object, Object>) textures.get(manager)).put(location, texture);
            if (texture instanceof AbstractTexture) {
                glId.setInt(texture, GL11.textureId(location.toString()));
            }
            return true;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }
}
