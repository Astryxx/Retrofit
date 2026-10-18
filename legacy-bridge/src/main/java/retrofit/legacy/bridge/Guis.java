package retrofit.legacy.bridge;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;
import cpw.mods.fml.common.network.NetworkRegistry;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import java.util.List;

/**
 * Legacy GUIs and containers, both sides.
 *
 * Server: FML's openGui is redirected here ({@link #openGui}). The mod's server container is
 * built as usual, then the host opens a 1.20.1 menu over it (HostPlayer.openMenu); the client
 * builds the mod's GUI from the same mod id, GUI id and position, as FML's OpenGui message did.
 * The menu's slots are views of the legacy slots, and clicks run the legacy container's slotClick
 * (shift-click included) on the player's mirror, so container logic is the mod's own.
 *
 * Client: see {@link ClientGuis}, which draws legacy GUIs through the GL emulation and feeds
 * them input; the redirect targets for client classes live there too.
 */
public final class Guis {
    private Guis() {
    }

    // ---- Opening (redirect target of FMLNetworkHandler.openGui) ----

    public static void openGui(EntityPlayer player, Object mod, int guiId, World world, int x, int y, int z) {
        ModContainer container = FMLCommonHandler.instance().findContainerFor(mod);
        if (container == null) {
            System.out.println("[Retrofit] openGui for an unknown mod object " + mod);
            return;
        }
        if (player instanceof BridgePlayerMP) {
            BridgePlayerMP serverPlayer = (BridgePlayerMP) player;
            Container menu = NetworkRegistry.INSTANCE.getRemoteGuiContainer(container, serverPlayer, guiId, world, x, y, z);
            if (menu != null) {
                open(serverPlayer, menu, container.getModId(), guiId, x, y, z);
            }
        } else if (world.field_72995_K) { // a client-side call opens a client-only GUI, as FML did
            ClientGuis.openLocal(player, container, guiId, world, x, y, z);
        }
    }

    /** Opens a host menu over a legacy server container and makes it the mirror's open container. */
    static void open(BridgePlayerMP player, Container menu, String modId, int guiId, int x, int y, int z) {
        int windowId = player.host.openMenu(menu, modId, guiId, x, y, z);
        if (windowId < 0) {
            return;
        }
        menu.field_75152_c = windowId; // windowId
        player.field_71070_bA = menu; // openContainer
        player.hostContainer = menu;
        menu.func_75132_a(player); // addCraftingToCrafters: initial progress bar values
    }

    /** guiId of a container a mod opened by itself; its client GUI comes from the mod's own packet. */
    public static final int DIRECT = -2;

    /**
     * Mods that open containers without FML's openGui (IC2: player.openContainer = container, then
     * its own packet makes the client show the GUI): after legacy code ran for a player, a container
     * the host doesn't know yet gets a host menu. The mod's window id is kept (its packets carry it).
     */
    static void adoptOpenContainer(BridgePlayerMP player) {
        Container open = player.field_71070_bA; // openContainer
        if (open == null || open == player.field_71069_bz || open == player.hostContainer) { // inventoryContainer
            return;
        }
        player.hostContainer = open;
        int legacyWindowId = open.field_75152_c;
        if (player.host.openMenu(open, "", DIRECT, 0, 0, 0) < 0) {
            player.field_71070_bA = player.field_71069_bz;
            return;
        }
        open.field_75152_c = legacyWindowId;
        player.field_71070_bA = open; // the host's open message must not have replaced it
    }

    /** The client GUI for a container the server opened: from the mod's GUI handler (or a vanilla GUI, guiId < 0). */
    public static Object clientGui(Object legacyPlayer, String modId, int guiId, int x, int y, int z) {
        EntityPlayer player = (EntityPlayer) legacyPlayer;
        if (guiId < 0) {
            return ClientGuis.vanillaGui(player, guiId, modId, x, y, z); // modId carries the title; x the size for chests
        }
        ModContainer container = Loader.instance().getIndexedModList().get(modId);
        return container == null ? null
                : NetworkRegistry.INSTANCE.getLocalGuiContainer(container, player, guiId, player.field_70170_p, x, y, z);
    }

    // ---- Containers (host menus call these on both sides) ----

    /** {slot count, then for each slot: x, y, index in the player's inventory or -1}. */
    @SuppressWarnings("unchecked")
    public static int[] containerSlots(Object legacyContainer) {
        List<Slot> slots = ((Container) legacyContainer).field_75151_b; // inventorySlots
        int[] layout = new int[1 + slots.size() * 3];
        layout[0] = slots.size();
        for (int i = 0; i < slots.size(); i++) {
            Slot slot = slots.get(i);
            layout[1 + i * 3] = slot.field_75223_e; // xDisplayPosition
            layout[2 + i * 3] = slot.field_75221_f; // yDisplayPosition
            boolean player = slot.field_75224_c instanceof InventoryPlayer && slot.getSlotIndex() < 40;
            layout[3 + i * 3] = player ? slot.getSlotIndex() : -1;
        }
        return layout;
    }

    private static Slot slot(Object legacyContainer, int index) {
        return (Slot) ((Container) legacyContainer).field_75151_b.get(index);
    }

    public static Object slotStack(Object legacyContainer, int index) {
        return slot(legacyContainer, index).func_75211_c(); // getStack
    }

    public static void setSlotStack(Object legacyContainer, int index, Object stack) {
        slot(legacyContainer, index).func_75215_d((ItemStack) stack); // putStack
    }

    public static boolean slotAccepts(Object legacyContainer, int index, Object stack) {
        return slot(legacyContainer, index).func_75214_a((ItemStack) stack); // isItemValid
    }

    public static int slotLimit(Object legacyContainer, int index) {
        return slot(legacyContainer, index).func_75219_a(); // getSlotStackLimit
    }

    public static boolean slotCanTake(Object legacyContainer, int index, Object legacyPlayer) {
        return slot(legacyContainer, index).func_82869_a((EntityPlayer) legacyPlayer); // canTakeStack
    }

    /** slotClick with the cursor stack in and out: returns the cursor stack afterwards. */
    public static Object click(Object legacyContainer, int slotId, int button, int mode, Object legacyPlayer, Object carried) {
        EntityPlayer player = (EntityPlayer) legacyPlayer;
        player.field_71071_by.func_70437_b((ItemStack) carried); // inventory.setItemStack
        ((Container) legacyContainer).func_75144_a(slotId, button, mode, player); // slotClick
        ItemStack after = player.field_71071_by.func_70445_o(); // getItemStack
        return after != null && after.field_77994_a <= 0 ? null : after;
    }

    /** detectAndSendChanges: progress bars reach the host through the mirror (sendProgressBarUpdate). */
    public static void detectChanges(Object legacyContainer) {
        ((Container) legacyContainer).func_75142_b();
    }

    public static boolean canInteract(Object legacyContainer, Object legacyPlayer) {
        return ((Container) legacyContainer).func_75145_c((EntityPlayer) legacyPlayer); // canInteractWith
    }

    /** onContainerClosed (mods drop crafting grids and save here); the mirror goes back to its inventory container. */
    public static void closeContainer(Object legacyContainer, Object legacyPlayer) {
        EntityPlayer player = (EntityPlayer) legacyPlayer;
        Container container = (Container) legacyContainer;
        container.func_75134_a(player);
        if (player instanceof BridgePlayerMP && ((BridgePlayerMP) player).hostContainer == container) {
            ((BridgePlayerMP) player).hostContainer = null;
        }
        if (player.field_71070_bA == container) { // openContainer
            player.field_71070_bA = player.field_71069_bz; // inventoryContainer
        }
    }

    /** Client: updateProgressBar */
    public static void progressBar(Object legacyContainer, int property, int value) {
        ((Container) legacyContainer).func_75137_b(property, value);
    }

    public static void setWindowId(Object legacyContainer, int windowId) {
        ((Container) legacyContainer).field_75152_c = windowId;
    }

    /** Mods check player.openContainer in their packet handlers. */
    public static void setOpenContainer(Object legacyPlayer, Object legacyContainer) {
        EntityPlayer player = (EntityPlayer) legacyPlayer;
        player.field_71070_bA = legacyContainer != null ? (Container) legacyContainer : player.field_71069_bz;
    }

    /** Server: a vanilla chest GUI over a mod's inventory (EntityPlayerMP.displayGUIChest). */
    static void openChest(BridgePlayerMP player, net.minecraft.inventory.IInventory inventory) {
        Container menu = new net.minecraft.inventory.ContainerChest(player.field_71071_by, inventory);
        open(player, menu, inventory.func_145825_b(), ClientGuis.CHEST, inventory.func_70302_i_(), 0, 0); // getInventoryName, getSizeInventory
    }

    /**
     * Server: the other vanilla GUIs EntityPlayerMP opens (displayGUIFurnace, displayGUIBeacon...),
     * over a mod's tile entity or at a position (crafting, enchanting, anvil). The client makes
     * the matching vanilla GUI, as 1.7.10's handleOpenWindow did.
     */
    static void openVanilla(BridgePlayerMP player, int kind, Object tile, int x, int y, int z, String title) {
        net.minecraft.entity.player.InventoryPlayer inventory = player.field_71071_by;
        Container menu;
        switch (kind) {
            case ClientGuis.FURNACE:
                menu = new net.minecraft.inventory.ContainerFurnace(inventory, (net.minecraft.tileentity.TileEntityFurnace) tile);
                break;
            case ClientGuis.DISPENSER:
                menu = new net.minecraft.inventory.ContainerDispenser(inventory, (net.minecraft.tileentity.TileEntityDispenser) tile);
                break;
            case ClientGuis.HOPPER:
                menu = new net.minecraft.inventory.ContainerHopper(inventory, (net.minecraft.inventory.IInventory) tile);
                break;
            case ClientGuis.BREWING:
                menu = new net.minecraft.inventory.ContainerBrewingStand(inventory, (net.minecraft.tileentity.TileEntityBrewingStand) tile);
                break;
            case ClientGuis.BEACON:
                menu = new net.minecraft.inventory.ContainerBeacon(inventory, (net.minecraft.tileentity.TileEntityBeacon) tile);
                break;
            case ClientGuis.WORKBENCH:
                menu = new net.minecraft.inventory.ContainerWorkbench(inventory, player.field_70170_p, x, y, z);
                break;
            case ClientGuis.ENCHANTMENT:
                menu = new net.minecraft.inventory.ContainerEnchantment(inventory, player.field_70170_p, x, y, z);
                break;
            case ClientGuis.ANVIL:
                menu = new net.minecraft.inventory.ContainerRepair(inventory, player.field_70170_p, x, y, z, player);
                break;
            default:
                return;
        }
        if (tile instanceof net.minecraft.tileentity.TileEntity) {
            net.minecraft.tileentity.TileEntity entity = (net.minecraft.tileentity.TileEntity) tile;
            x = entity.field_145851_c;
            y = entity.field_145848_d;
            z = entity.field_145849_e;
        }
        open(player, menu, title == null ? "" : title, kind, x, y, z);
    }
}
