package retrofit.forge.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;
import retrofit.api.HostInventory;

import java.util.stream.IntStream;

/**
 * A 1.20.1 block entity's inventory (vanilla chests, furnaces, hoppers, or any block with an item
 * handler) for legacy code: BuildCraft pipes, IC2 machines and the like pull from and push into
 * it as they did with 1.7.10's tile entities. Stacks go through {@link LegacyStacks}; items with no
 * 1.7.10 form are invisible (and never overwritten).
 */
abstract class HostContainerInventory implements HostInventory {
    final String name;

    private HostContainerInventory(String name) {
        this.name = name;
    }

    /** The inventory at a position, or null if the block entity there has none (or is a legacy one). */
    static HostInventory at(Level level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null || blockEntity instanceof LegacyBlockEntity) {
            return null;
        }
        BlockState state = blockEntity.getBlockState();
        String name = state.getBlock().getDescriptionId();
        Container container = null;
        if (state.getBlock() instanceof ChestBlock chest) {
            container = ChestBlock.getContainer(chest, state, level, pos, true); // both halves of a double chest
        }
        if (container == null && blockEntity instanceof Container plain) {
            container = plain;
        }
        if (container != null) {
            return new OfContainer(name, container);
        }
        IItemHandler handler = blockEntity.getCapability(ForgeCapabilities.ITEM_HANDLER).orElse(null);
        return handler == null ? null : new OfHandler(name, handler);
    }

    @Override
    public String name() {
        return name;
    }

    abstract ItemStack get(int slot);

    abstract void set(int slot, ItemStack stack);

    @Override
    public Object getStack(int slot) {
        if (slot < 0 || slot >= size()) {
            return null;
        }
        return LegacyStacks.toLegacy(get(slot));
    }

    @Override
    public void setStack(int slot, Object legacyStack) {
        if (slot < 0 || slot >= size()) {
            return;
        }
        ItemStack current = get(slot);
        if (!current.isEmpty() && LegacyStacks.toLegacy(current) == null) {
            return; // a 1.20.1-only item legacy code can't see: it stays
        }
        set(slot, LegacyStacks.toModern(legacyStack));
    }

    private static Direction face(int side) {
        return side >= 0 && side < 6 ? Direction.from3DDataValue(side) : Direction.UP;
    }

    /** Vanilla containers: chests, furnaces, hoppers, dispensers, brewing stands, barrels, shulker boxes... */
    private static final class OfContainer extends HostContainerInventory {
        private final Container container;

        OfContainer(String name, Container container) {
            super(name);
            this.container = container;
        }

        @Override
        public int size() {
            return container.getContainerSize();
        }

        @Override
        ItemStack get(int slot) {
            return container.getItem(slot);
        }

        @Override
        void set(int slot, ItemStack stack) {
            container.setItem(slot, stack);
        }

        @Override
        public int stackLimit() {
            return container.getMaxStackSize();
        }

        @Override
        public boolean canPlace(int slot, Object legacyStack) {
            ItemStack stack = LegacyStacks.toModern(legacyStack);
            return !stack.isEmpty() && container.canPlaceItem(slot, stack);
        }

        @Override
        public int[] slotsForSide(int side) {
            if (container instanceof WorldlyContainer worldly) {
                return worldly.getSlotsForFace(face(side));
            }
            return IntStream.range(0, size()).toArray();
        }

        @Override
        public boolean canInsert(int slot, Object legacyStack, int side) {
            ItemStack stack = LegacyStacks.toModern(legacyStack);
            if (stack.isEmpty() || !container.canPlaceItem(slot, stack)) {
                return false;
            }
            return !(container instanceof WorldlyContainer worldly) || worldly.canPlaceItemThroughFace(slot, stack, face(side));
        }

        @Override
        public boolean canExtract(int slot, Object legacyStack, int side) {
            ItemStack current = get(slot);
            if (current.isEmpty() || LegacyStacks.toLegacy(current) == null) {
                return false;
            }
            return !(container instanceof WorldlyContainer worldly) || worldly.canTakeItemThroughFace(slot, current, face(side));
        }

        @Override
        public void changed() {
            container.setChanged();
        }
    }

    /** Other blocks with Forge's item handler (modern mods' machines and storage). */
    private static final class OfHandler extends HostContainerInventory {
        private final IItemHandler handler;

        OfHandler(String name, IItemHandler handler) {
            super(name);
            this.handler = handler;
        }

        @Override
        public int size() {
            return handler.getSlots();
        }

        @Override
        ItemStack get(int slot) {
            return handler.getStackInSlot(slot);
        }

        @Override
        void set(int slot, ItemStack stack) {
            if (handler instanceof IItemHandlerModifiable modifiable) {
                modifiable.setStackInSlot(slot, stack);
                return;
            }
            ItemStack current = handler.getStackInSlot(slot);
            if (!current.isEmpty()) {
                handler.extractItem(slot, current.getCount(), false);
            }
            if (!stack.isEmpty()) {
                handler.insertItem(slot, stack, false);
            }
        }

        @Override
        public int stackLimit() {
            return handler.getSlots() == 0 ? 64 : Math.min(64, handler.getSlotLimit(0));
        }

        @Override
        public boolean canPlace(int slot, Object legacyStack) {
            ItemStack stack = LegacyStacks.toModern(legacyStack);
            return !stack.isEmpty() && handler.isItemValid(slot, stack);
        }

        @Override
        public int[] slotsForSide(int side) {
            return IntStream.range(0, size()).toArray();
        }

        @Override
        public boolean canInsert(int slot, Object legacyStack, int side) {
            ItemStack stack = LegacyStacks.toModern(legacyStack);
            return !stack.isEmpty() && handler.insertItem(slot, stack, true).getCount() < stack.getCount();
        }

        @Override
        public boolean canExtract(int slot, Object legacyStack, int side) {
            ItemStack current = get(slot);
            return !current.isEmpty() && LegacyStacks.toLegacy(current) != null && !handler.extractItem(slot, 1, true).isEmpty();
        }

        @Override
        public void changed() {
        }
    }
}
