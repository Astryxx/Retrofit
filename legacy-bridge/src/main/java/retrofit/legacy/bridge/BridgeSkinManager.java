package retrofit.legacy.bridge;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.resources.SkinManager;

/** Client only. Player skins are the host's business; legacy players never load their own. */
final class BridgeSkinManager extends SkinManager {
    BridgeSkinManager() {
        super(null, null, null);
    }

    /** loadProfileTextures */
    @Override
    public void func_152790_a(GameProfile profile, SkinManager.SkinAvailableCallback callback, boolean requireSecure) {
    }
}
