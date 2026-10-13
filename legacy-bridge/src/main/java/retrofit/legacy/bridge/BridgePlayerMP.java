package retrofit.legacy.bridge;

import com.mojang.authlib.GameProfile;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.ItemInWorldManager;
import net.minecraft.util.IChatComponent;
import net.minecraft.world.WorldServer;
import retrofit.api.HostPlayer;

/**
 * The legacy mirror of a server player: a real EntityPlayerMP, so mods see the player type they
 * expect. {@link LegacyPlayers} copies the host player's state into it around legacy calls; it
 * is never ticked or saved itself.
 */
final class BridgePlayerMP extends EntityPlayerMP {
    final HostPlayer host;

    BridgePlayerMP(MinecraftServer server, WorldServer world, GameProfile profile, HostPlayer host) {
        super(server, world, profile, new ItemInWorldManager(world));
        this.host = host;
        new BridgeNetHandler(server, this, host); // sets playerNetServerHandler
    }

    /** addChatMessage */
    @Override
    public void func_145747_a(IChatComponent component) {
        host.sendMessage(IChatComponent.Serializer.func_150696_a(component));
    }
}
