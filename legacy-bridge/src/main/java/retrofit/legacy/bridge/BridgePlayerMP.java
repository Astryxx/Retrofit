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
    /** The legacy container the host has a menu for (see Guis). */
    Object hostContainer;

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

    // The other vanilla GUIs (mods reuse them: Chisel's beacon, mod furnaces and dispensers).

    /** displayGUIFurnace */
    @Override
    public void func_146101_a(net.minecraft.tileentity.TileEntityFurnace tile) {
        Guis.openVanilla(this, ClientGuis.FURNACE, tile, 0, 0, 0, tile.func_145825_b());
    }

    /** displayGUIDispenser */
    @Override
    public void func_146102_a(net.minecraft.tileentity.TileEntityDispenser tile) {
        Guis.openVanilla(this, ClientGuis.DISPENSER, tile, 0, 0, 0, tile.func_145825_b());
    }

    /** displayGUIHopper */
    @Override
    public void func_146093_a(net.minecraft.tileentity.TileEntityHopper tile) {
        Guis.openVanilla(this, ClientGuis.HOPPER, tile, 0, 0, 0, tile.func_145825_b());
    }

    /** displayGUIBrewingStand */
    @Override
    public void func_146098_a(net.minecraft.tileentity.TileEntityBrewingStand tile) {
        Guis.openVanilla(this, ClientGuis.BREWING, tile, 0, 0, 0, tile.func_145825_b());
    }

    /** displayGUIBeacon */
    @Override
    public void func_146104_a(net.minecraft.tileentity.TileEntityBeacon tile) {
        Guis.openVanilla(this, ClientGuis.BEACON, tile, 0, 0, 0, tile.func_145825_b());
    }

    /** displayGUIWorkbench */
    @Override
    public void func_71058_b(int x, int y, int z) {
        Guis.openVanilla(this, ClientGuis.WORKBENCH, null, x, y, z, null);
    }

    /** displayGUIEnchantment */
    @Override
    public void func_71002_c(int x, int y, int z, String name) {
        Guis.openVanilla(this, ClientGuis.ENCHANTMENT, null, x, y, z, name);
    }

    /** displayGUIAnvil */
    @Override
    public void func_82244_d(int x, int y, int z) {
        Guis.openVanilla(this, ClientGuis.ANVIL, null, x, y, z, null);
    }

    /** addStat: achievements mods unlock become the player's advancements (see Achievements). */
    @Override
    public void func_71064_a(net.minecraft.stats.StatBase stat, int amount) {
        try {
            super.func_71064_a(stat, amount);
        } catch (RuntimeException e) {
            // the legacy statistics file: not needed, the host keeps the player's progress
        }
        if (stat instanceof net.minecraft.stats.Achievement && amount > 0) {
            host.grantAchievement(stat.field_75975_e); // statId
        }
    }

    /** canCommandSenderUseCommand: the host's permission level (1.7.10 asked its own ops list). */
    @Override
    public boolean func_70003_b(int level, String command) {
        return "tell".equals(command) || "help".equals(command) || "me".equals(command) || host.permissionLevel() >= level;
    }

    /** attackEntityFrom: legacy mobs, projectiles and effects hurt the real player. */
    @Override
    public boolean func_70097_a(net.minecraft.util.DamageSource source, float amount) {
        return LivingEvents.damageHost(host, this, source, amount);
    }

    /** travelToDimension: the host moves the player (see BridgePlayerList for mods' teleporters). */
    @Override
    public void func_71027_c(int dimension) {
        host.changeDimension(dimension, null);
    }

    /** addChatMessage */
    @Override
    public void func_145747_a(IChatComponent component) {
        host.sendMessage(IChatComponent.Serializer.func_150696_a(component));
    }
}
