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

    /** func_152602_a: the statistics file of a player */
    @Override
    public StatisticsFile func_152602_a(EntityPlayer player) {
        return new StatisticsFile(server, new File(directory, "stats/" + player.func_110124_au() + ".json")); // getUniqueID
    }
}
