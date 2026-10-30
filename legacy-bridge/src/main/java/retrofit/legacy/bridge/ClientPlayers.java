package retrofit.legacy.bridge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
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

    static void forgetWorld(World world) {
        Minecraft minecraft = Minecraft.func_71410_x();
        if (minecraft.field_71439_g != null && minecraft.field_71439_g.field_70170_p == world) minecraft.field_71439_g = null;
        if (minecraft.field_71451_h != null && minecraft.field_71451_h.field_70170_p == world) minecraft.field_71451_h = null;
        if (minecraft.field_71441_e == world) minecraft.field_71441_e = null;
    }

    static EntityPlayer create(World world, HostPlayer host, String uuid, String name) {
        Minecraft minecraft = Minecraft.func_71410_x(); // getMinecraft
        replaceSkinManager(minecraft);
        Session session = new Session(name, uuid.replace("-", ""), "-", "legacy");
        BridgeClientPlayer player = new BridgeClientPlayer(minecraft, world, session, host);
        makeCurrent(player);
        return player;
    }

    /** Another player, as a 1.7.10 client knew it (EntityOtherPlayerMP): drawn, never Minecraft.thePlayer. */
    static EntityPlayer createOther(World world, String uuid, String name) {
        return new net.minecraft.client.entity.EntityOtherPlayerMP(world, new com.mojang.authlib.GameProfile(java.util.UUID.fromString(uuid), name));
    }

    /**
     * The arm's lag behind the view, as EntityPlayerSP.onLivingUpdate moved it each tick: renderers
     * of held items sway by the difference (Thaumcraft's thaumometer). Left at 0 the item swung by
     * the whole yaw, and jumped as it grew.
     */
    static void tickArm(EntityPlayer player) {
        if (!(player instanceof BridgeClientPlayer)) {
            return;
        }
        BridgeClientPlayer client = (BridgeClientPlayer) player;
        if (!client.armTicked) {
            client.armTicked = true;
            client.field_71154_f = client.field_70177_z; // renderArmYaw = rotationYaw
            client.field_71155_g = client.field_70125_A; // renderArmPitch = rotationPitch
        }
        client.field_71163_h = client.field_71154_f; // prevRenderArmYaw
        client.field_71164_i = client.field_71155_g; // prevRenderArmPitch
        client.field_71155_g += (client.field_70125_A - client.field_71155_g) * 0.5F;
        client.field_71154_f += (client.field_70177_z - client.field_71154_f) * 0.5F;
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
            if (minecraft.field_71462_r == BLANK_SCREEN) { // currentScreen
                minecraft.field_71462_r = null; // currentScreen: back in game, no screen
            }
        } else if (minecraft != null && minecraft.field_71462_r == null) { // currentScreen
            minecraft.field_71462_r = BLANK_SCREEN; // currentScreen
        }
    }

    /**
     * The screen 1.7.10 always had up while there was no player (its menus and loading screens are
     * GuiScreens): mods read currentScreen == null as "in game" and then use thePlayer, which is
     * null until the host player exists (AoA's rageRenderer takes null for an answer to neither).
     * While no player mirror is current and no legacy screen is up, a blank one shows instead, so
     * that check reads as it did. Set directly, not through ClientGuis.displayScreen: the host
     * must not open a screen of its own for this.
     */
    private static final GuiScreen BLANK_SCREEN = new GuiScreen() {
    };

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
