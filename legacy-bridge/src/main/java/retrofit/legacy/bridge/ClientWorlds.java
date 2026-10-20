package retrofit.legacy.bridge;

import net.minecraft.client.Minecraft;
import net.minecraft.world.World;
import retrofit.api.HostWorld;

/**
 * Creates client worlds. Kept apart from LegacyWorlds so a dedicated server, whose 1.7.10 jar has
 * no WorldClient, never loads BridgeWorldClient (the verifier would, to check LegacyWorlds).
 */
final class ClientWorlds {
    private ClientWorlds() {
    }

    static World create(HostWorld host, int dimension) {
        BridgeWorldClient world = new BridgeWorldClient(host, dimension);
        Minecraft minecraft = Minecraft.func_71410_x(); // getMinecraft
        synchronized (ClientWorlds.class) {
            if (minecraft.field_71452_i == null) { // effectRenderer
                minecraft.field_71452_i = new BridgeEffectRenderer(world);
            }
        }
        return world;
    }
}
