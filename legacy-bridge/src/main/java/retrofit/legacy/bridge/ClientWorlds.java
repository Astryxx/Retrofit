package retrofit.legacy.bridge;

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
        return new BridgeWorldClient(host, dimension);
    }
}
