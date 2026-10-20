package retrofit.legacy.bridge;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.WorldSettings;

import java.io.File;
import java.net.Proxy;

/**
 * The legacy MinecraftServer (MinecraftServer.getServer()): a real one, so mods find a server, its
 * player list, its worlds and its commands. It never runs its own loop, network or saving; the
 * host server does all of that. Create it through {@link LegacyWorlds}, which first moves the
 * files it touches (user cache, ops, bans, whitelist) into the Retrofit folder.
 */
public final class BridgeServer extends MinecraftServer {
    private final boolean dedicated;

    public BridgeServer(File directory, boolean dedicated) {
        super(directory, Proxy.NO_PROXY);
        this.dedicated = dedicated;
        func_152361_a(new BridgePlayerList(this, directory)); // setConfigurationManager
    }

    /**
     * getFolderName and getActiveAnvilConverter: where 1.7.10 kept the world. Forge's
     * DimensionManager.getCurrentSaveRootDirectory builds a save handler from them when no world is
     * loaded yet (BuildCraft asks while the server starts): the host world's "retrofit" folder.
     */
    @Override
    public String func_71270_I() {
        return LegacyWorlds.saveDirectory().getName();
    }

    @Override
    public net.minecraft.world.storage.ISaveFormat func_71254_M() {
        File parent = LegacyWorlds.saveDirectory().getAbsoluteFile().getParentFile();
        return new net.minecraft.world.chunk.storage.AnvilSaveConverter(parent);
    }

    /** startServer: never started; the host runs the server. */
    @Override
    protected boolean func_71197_b() {
        return true;
    }

    /** canStructuresSpawn */
    @Override
    public boolean func_71225_e() {
        return true;
    }

    /** getGameType */
    @Override
    public WorldSettings.GameType func_71265_f() {
        return WorldSettings.GameType.SURVIVAL;
    }

    /** getDifficulty */
    @Override
    public EnumDifficulty func_147135_j() {
        return EnumDifficulty.NORMAL;
    }

    /** isHardcore */
    @Override
    public boolean func_71199_h() {
        return false;
    }

    /** getOpPermissionLevel */
    @Override
    public int func_110455_j() {
        return 4;
    }

    @Override
    public boolean func_152363_m() {
        return false;
    }

    /** isDedicatedServer */
    @Override
    public boolean func_71262_S() {
        return dedicated;
    }

    /** isCommandBlockEnabled */
    @Override
    public boolean func_82356_Z() {
        return false;
    }

    /** shareToLAN: the host does that. */
    @Override
    public String func_71206_a(WorldSettings.GameType type, boolean cheats) {
        return null;
    }
}
