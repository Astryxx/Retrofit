package retrofit.forge.world;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.extensions.IForgeMenuType;

/**
 * A 1.20.1 menu over a legacy container, on both sides.
 *
 * - Slots: those of the player's inventory are the real 1.20.1 inventory slots; the others are
 *   {@link LegacySlot} views of the legacy slots. Positions are the legacy ones.
 * - Clicks: every click (including shift-click, drag-splitting, number keys and double-click,
 *   whose 1.20.1 click types match 1.7.10's modes) runs the legacy container's slotClick on the
 *   player's mirror, with the cursor stack carried across. On clients that is the prediction;
 *   the server's result is synced back by 1.20.1 as usual.
 * - Every server tick the legacy container's detectAndSendChanges runs first, so progress bars
 *   reach the client ({@link LegacyNetwork.WindowProperty}).
 *
 * Servers open it from the legacy openGui (see LegacyPlayers); clients build the mod's GUI from
 * the extra data (mod id, GUI id, position), as FML's OpenGui message did.
 */
public class LegacyMenu extends AbstractContainerMenu {
    /** Window id of client-only container GUIs (opened by client code): the server ignores their clicks. */
    public static final int CLIENT_ONLY = -3;
    /** guiId of a container a mod opened without FML's openGui (the legacy bridge's Guis.DIRECT). */
    static final int DIRECT = -2;

    private static MenuType<LegacyMenu> type;

    private final Object container;
    private final Object screen;
    private final Player player;

    public static MenuType<LegacyMenu> type() {
        if (type == null) {
            type = IForgeMenuType.create(LegacyMenu::fromNetwork);
        }
        return type;
    }

    private LegacyMenu(int id, Player player, Object container, Object screen) {
        super(type(), id);
        this.container = container;
        this.screen = screen;
        this.player = player;
        if (container == null) {
            return;
        }
        LegacyBridge.call("setWindowId", container, id);
        int[] layout = LegacyBridge.call("containerSlots", container);
        Container none = new SimpleContainer(0);
        for (int i = 0; i < layout[0]; i++) {
            int x = layout[1 + i * 3];
            int y = layout[2 + i * 3];
            int inventoryIndex = layout[3 + i * 3];
            addSlot(inventoryIndex >= 0 ? new Slot(player.getInventory(), inventoryIndex, x, y) : new LegacySlot(none, this, i, x, y));
        }
    }

    /** Server: a menu over the legacy container a mod's GUI handler made. */
    static LegacyMenu server(int id, Player player, Object container) {
        return new LegacyMenu(id, player, container, null);
    }

    /** Client, from the server's open message: the mod's own client GUI and its container. */
    private static LegacyMenu fromNetwork(int id, Inventory inventory, FriendlyByteBuf data) {
        String modId = data.readUtf();
        int guiId = data.readVarInt();
        BlockPos pos = data.readBlockPos();
        Player player = inventory.player;
        Object screen = null;
        Object container = null;
        try {
            Object mirror = LegacyPlayers.mirror(player, true);
            if (guiId == DIRECT) {
                // A container the mod opened itself: its own packet already put its GUI on screen (client-only so far).
                screen = retrofit.forge.client.ClientHooks.takeClientOnlyScreen();
                container = screen == null ? null : LegacyBridge.call("guiContainer", screen);
                return new LegacyMenu(id, player, container, screen);
            }
            screen = LegacyBridge.call("clientGui", mirror, modId, guiId, pos.getX(), pos.getY(), pos.getZ());
            container = screen == null ? null : LegacyBridge.call("guiContainer", screen);
            if (container != null) {
                LegacyBridge.call("setOpenContainer", mirror, container);
            }
        } catch (RuntimeException e) {
            LegacyErrors.report("GUI " + modId + "#" + guiId, "opening", e);
        }
        return new LegacyMenu(id, player, container, screen);
    }

    /** Client: a container GUI legacy client code opened by itself. */
    public static LegacyMenu clientOnly(Player player, Object screen) {
        return new LegacyMenu(CLIENT_ONLY, player, LegacyBridge.call("guiContainer", screen), screen);
    }

    public Object legacyContainer() {
        return container;
    }

    /** Client: the legacy GUI screen, or null if it couldn't be made (the screen then closes). */
    public Object legacyScreen() {
        return screen;
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player clicker) {
        if (container == null) {
            return;
        }
        ItemStack carried = getCarried();
        Object legacyCarried = LegacyStacks.toLegacy(carried);
        if (!carried.isEmpty() && legacyCarried == null) {
            // A 1.20.1-only item on the cursor: legacy code can't hold it, but the player's own slots can.
            if (slotId >= 0 && slotId < slots.size() && !(slots.get(slotId) instanceof LegacySlot)) {
                super.clicked(slotId, button, clickType, clicker);
            }
            return;
        }
        try {
            Object mirror = LegacyPlayers.mirror(clicker, true);
            Object after = LegacyBridge.call("click", container, slotId, button, clickType.ordinal(), mirror, legacyCarried);
            LegacyPlayers.writeBackContainer(clicker, mirror);
            setCarried(LegacyStacks.toModern(after));
        } catch (RuntimeException e) {
            LegacyErrors.report("container " + container.getClass().getName(), "handling a click", e);
        }
    }

    /**
     * Client: the server's contents. Some mod containers are sized by state the client doesn't
     * have yet (the held item's NBT, a tile entity not synced yet), so the server's may have more
     * slots than this one: those extra slots are left out instead of failing the whole update.
     */
    @Override
    public void initializeContents(int stateId, java.util.List<ItemStack> items, ItemStack carried) {
        super.initializeContents(stateId, items.size() > slots.size() ? items.subList(0, slots.size()) : items, carried);
    }

    @Override
    public void setItem(int slotId, int stateId, ItemStack stack) {
        if (slotId >= 0 && slotId < slots.size()) {
            super.setItem(slotId, stateId, stack);
        }
    }

    @Override
    public ItemStack quickMoveStack(Player clicker, int index) {
        return ItemStack.EMPTY; // shift-clicks go through clicked(), to the legacy container's transferStackInSlot
    }

    @Override
    public boolean stillValid(Player viewer) {
        if (container == null) {
            return false;
        }
        if (viewer.level().isClientSide) {
            return true;
        }
        try {
            return LegacyBridge.call("canInteract", container, LegacyPlayers.mirror(viewer, false));
        } catch (RuntimeException e) {
            LegacyErrors.report("container " + container.getClass().getName(), "checking the player can use it", e);
            return false;
        }
    }

    @Override
    public void broadcastChanges() {
        if (container != null && !player.level().isClientSide) {
            try {
                LegacyBridge.call("detectChanges", container);
            } catch (RuntimeException e) {
                LegacyErrors.report("container " + container.getClass().getName(), "updating", e);
            }
        }
        super.broadcastChanges();
    }

    @Override
    public void removed(Player viewer) {
        super.removed(viewer);
        if (container != null && !viewer.level().isClientSide) {
            try {
                Object mirror = LegacyPlayers.mirror(viewer, true);
                LegacyBridge.call("closeContainer", container, mirror);
                LegacyPlayers.writeBack(viewer, mirror);
            } catch (RuntimeException e) {
                LegacyErrors.report("container " + container.getClass().getName(), "closing", e);
            }
        }
        // Clients: the legacy GUI's onGuiClosed closes its container (see LegacyScreen).
    }

    /** Client: a property from the server (see LegacyNetwork.WindowProperty). */
    public void legacyProperty(int property, int value) {
        if (container != null) {
            LegacyBridge.call("progressBar", container, property, value);
        }
    }
}
