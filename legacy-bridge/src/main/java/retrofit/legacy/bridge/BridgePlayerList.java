package retrofit.legacy.bridge;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.ServerConfigurationManager;
import net.minecraft.stats.StatisticsFile;

import java.io.File;

/**
 * The legacy player list. Bridged players (see {@link LegacyPlayers}) are added to its
 * playerEntityList, so mods that iterate or message all players reach them. Statistics go to the
 * Retrofit folder and are not kept: the host owns player data.
 */
final class BridgePlayerList extends ServerConfigurationManager {
    private final MinecraftServer server;
    private final File directory;

    BridgePlayerList(MinecraftServer server, File directory) {
        super(server);
        this.server = server;
        this.directory = directory;
    }

    /** transferPlayerToDimension: the host moves the player; vanilla placement. */
    @Override
    public void func_72356_a(net.minecraft.entity.player.EntityPlayerMP player, int dimension) {
        transferPlayerToDimension(player, dimension, null);
    }

    /**
     * Forge's transferPlayerToDimension with the mod's Teleporter: the host moves the player, then the
     * teleporter places it in the new dimension (see Dimensions.placeInPortal). The plain vanilla
     * Teleporter is left to the host's own portal logic.
     */
    @Override
    public void transferPlayerToDimension(net.minecraft.entity.player.EntityPlayerMP player, int dimension, net.minecraft.world.Teleporter teleporter) {
        if (player instanceof BridgePlayerMP) {
            boolean vanilla = teleporter == null || teleporter.getClass() == net.minecraft.world.Teleporter.class;
            ((BridgePlayerMP) player).host.changeDimension(dimension, vanilla ? null : teleporter);
        }
    }

    /** func_152602_a: the statistics file of a player */
    @Override
    public StatisticsFile func_152602_a(EntityPlayer player) {
        return new StatisticsFile(server, new File(directory, "stats/" + player.func_110124_au() + ".json")); // getUniqueID
    }
}
