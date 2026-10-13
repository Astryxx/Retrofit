package retrofit.legacy.bridge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.stats.StatFileWriter;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.Session;
import net.minecraft.world.World;
import retrofit.api.HostPlayer;

/**
 * Client only. The legacy mirror of the local player, set as Minecraft.thePlayer: client-side
 * mod code (GUIs, renderers, item effects) reads it. Never ticked; {@link LegacyPlayers} copies
 * the host player's state into it.
 */
final class BridgeClientPlayer extends EntityClientPlayerMP {
    final HostPlayer host;

    BridgeClientPlayer(Minecraft minecraft, World world, Session session, HostPlayer host) {
        super(minecraft, world, session, LegacyWorlds.clientNetHandler(), new StatFileWriter());
        this.host = host;
    }

    /** closeScreen: the host closes its screen (and tells the server, as 1.20.1 does). */
    @Override
    public void func_71053_j() {
        host.closeMenu();
    }

    /** addChatMessage: shown in the host's chat. */
    @Override
    public void func_145747_a(IChatComponent component) {
        host.sendMessage(IChatComponent.Serializer.func_150696_a(component));
    }
}
