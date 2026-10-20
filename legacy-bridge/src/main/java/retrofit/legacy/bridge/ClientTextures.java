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
    /**
     * The texture of worn armor, as RenderBiped.getArmorResource named it: Forge's getArmorTexture
     * (asked with the local player, as mods expect an entity), else the render index's prefix,
     * which mods add to (RenderingRegistry.addNewArmourRendererPrefix).
     */
    public static String armorTexture(Object legacyStack, int slot, String type) {
        net.minecraft.item.ItemStack stack = (net.minecraft.item.ItemStack) legacyStack;
        net.minecraft.entity.Entity wearer = net.minecraft.client.Minecraft.func_71410_x().field_71439_g; // thePlayer
        String texture = stack.func_77973_b().getArmorTexture(stack, wearer, slot, type);
        if (texture == null && stack.func_77973_b() instanceof net.minecraft.item.ItemArmor) {
            String[] prefixes = net.minecraft.client.renderer.entity.RenderBiped.field_82424_k; // bipedArmorFilenamePrefix
            int index = ((net.minecraft.item.ItemArmor) stack.func_77973_b()).field_77880_c; // renderIndex
            String prefix = prefixes[Math.max(0, Math.min(index, prefixes.length - 1))];
            texture = "minecraft:textures/models/armor/" + prefix + "_layer_" + (slot == 2 ? 2 : 1) + (type == null ? "" : "_" + type) + ".png";
        }
        return texture;
    }

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
