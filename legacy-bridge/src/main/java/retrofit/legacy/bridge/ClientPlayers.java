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

    /** Minecraft.thePlayer and theWorld follow the local player's mirror. */
    static void makeCurrent(EntityPlayer player) {
        Minecraft minecraft = Minecraft.func_71410_x();
        if (player instanceof BridgeClientPlayer) {
            minecraft.field_71439_g = (BridgeClientPlayer) player; // thePlayer
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
