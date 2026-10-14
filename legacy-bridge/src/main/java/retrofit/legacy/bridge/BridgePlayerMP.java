package retrofit.legacy.bridge;

import com.mojang.authlib.GameProfile;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
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

    /** sendProgressBarUpdate: container properties go to the client through the host's menu. */
    @Override
    public void func_71112_a(Container container, int property, int value) {
        host.windowProperty(container.field_75152_c, property, value); // windowId
    }

    /** sendSlotContents: the host syncs slots itself. */
    @Override
    public void func_71111_a(Container container, int slot, ItemStack stack) {
    }

    /** sendContainerAndContentsToPlayer: the host syncs slots itself. */
    @Override
    public void func_71110_a(Container container, java.util.List stacks) {
    }

    /** closeScreen: legacy code closed the GUI; the host closes its menu (which closes the legacy container). */
    @Override
    public void func_71053_j() {
        host.closeMenu();
    }

    /** displayGUIChest: a vanilla chest GUI over a mod's inventory. */
    @Override
    public void func_71007_a(IInventory inventory) {
        Guis.openChest(this, inventory);
    }

    /** canCommandSenderUseCommand: the host's permission level (1.7.10 asked its own ops list). */
    @Override
    public boolean func_70003_b(int level, String command) {
        return "tell".equals(command) || "help".equals(command) || "me".equals(command) || host.permissionLevel() >= level;
    }

    /** attackEntityFrom: legacy mobs, projectiles and effects hurt the real player. */
    @Override
    public boolean func_70097_a(net.minecraft.util.DamageSource source, float amount) {
        return host.hurt(source.field_76373_n, source.func_76346_g(), amount); // damageType, getEntity
    }

    /** addChatMessage */
    @Override
    public void func_145747_a(IChatComponent component) {
        host.sendMessage(IChatComponent.Serializer.func_150696_a(component));
    }
}
