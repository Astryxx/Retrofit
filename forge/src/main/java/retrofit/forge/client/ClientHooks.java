package retrofit.forge.client;

import net.minecraft.client.Minecraft;

/** Client-only calls made from common code; only invoked on the client, so this class only loads there. */
public final class ClientHooks {
    private ClientHooks() {
    }

    public static void setBlocksDirty(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.levelRenderer != null) {
            minecraft.levelRenderer.setBlocksDirty(minX, minY, minZ, maxX, maxY, maxZ);
        }
    }
}
