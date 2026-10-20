package retrofit.legacy.bridge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.Session;
import net.minecraft.world.World;
import retrofit.api.HostPlayer;

import java.lang.reflect.Field;

/** Client only (kept apart from LegacyPlayers so dedicated servers never load client classes). */
final class ClientPlayers {
    private static boolean skinsReplaced;

    private ClientPlayers() {
    }

    static EntityPlayer create(World world, HostPlayer host, String uuid, String name) {
        Minecraft minecraft = Minecraft.func_71410_x(); // getMinecraft
        replaceSkinManager(minecraft);
        Session session = new Session(name, uuid.replace("-", ""), "-", "legacy");
        BridgeClientPlayer player = new BridgeClientPlayer(minecraft, world, session, host);
        makeCurrent(player);
        return player;
    }

    /** The mirror's movementInput.sneak (EntityPlayerSP.isSneaking reads it). */
    static void setSneaking(EntityPlayer player, boolean sneaking) {
        if (player instanceof BridgeClientPlayer && ((BridgeClientPlayer) player).field_71158_b != null) {
            ((BridgeClientPlayer) player).field_71158_b.field_78899_d = sneaking;
        }
    }

    /**
     * Outside world rendering, Minecraft.renderViewEntity is the player, as on 1.7.10 (mods' tick
     * handlers check it: Thaumcraft makes its research popup only if it's a player). Rendering
     * points it at the camera (LegacyRendering.prepareFrame).
     */
    static void viewFromPlayer() {
        Minecraft minecraft = Minecraft.func_71410_x();
        if (minecraft != null && minecraft.field_71439_g != null) { // thePlayer
            minecraft.field_71451_h = minecraft.field_71439_g; // renderViewEntity
        }
    }

    /** Minecraft.thePlayer and theWorld follow the local player's mirror. */
    static void makeCurrent(EntityPlayer player) {
        Minecraft minecraft = Minecraft.func_71410_x();
        if (player instanceof BridgeClientPlayer) {
            minecraft.field_71439_g = (BridgeClientPlayer) player; // thePlayer
        }
        if (minecraft.field_71456_v == null) { // ingameGUI: mods print chat through it
            minecraft.field_71456_v = new BridgeGuiIngame(minecraft);
        }
        if (minecraft.field_71442_b == null) { // playerController: mods read the game mode and reach from it (HUDs, tools)
            minecraft.field_71442_b = new net.minecraft.client.multiplayer.PlayerControllerMP(minecraft, LegacyWorlds.clientNetHandler());
        }
        // its game mode follows the player's (LegacyPlayers.sync sets the capabilities from the host)
        net.minecraft.world.WorldSettings.GameType mode = player.field_71075_bZ.field_75098_d ? net.minecraft.world.WorldSettings.GameType.CREATIVE
                : net.minecraft.world.WorldSettings.GameType.SURVIVAL; // capabilities.isCreativeMode
        if (minecraft.field_71442_b.func_78758_h() != (mode == net.minecraft.world.WorldSettings.GameType.CREATIVE)) { // isInCreativeMode
            minecraft.field_71442_b.func_78746_a(mode); // setGameType
        }
        if (player.field_70170_p instanceof WorldClient) { // worldObj
            minecraft.field_71441_e = (WorldClient) player.field_70170_p; // theWorld
        }
    }

    private static synchronized void replaceSkinManager(Minecraft minecraft) {
        if (skinsReplaced) {
            return;
        }
        try {
            Field field = Minecraft.class.getDeclaredField("field_152350_aA"); // skinManager
            field.setAccessible(true);
            field.set(minecraft, new BridgeSkinManager());
            skinsReplaced = true;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retrofit could not set up legacy client players", e);
        }
    }
}
