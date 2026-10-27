RF = '''
        // CoFH RF (IEnergyHandler): the host block's Forge Energy, same unit.

        @Override
        public boolean canConnectEnergy(net.minecraftforge.common.util.ForgeDirection from) {
            HostEnergy energy = slots().energy;
            int side = Energy.side(from);
            return energy != null && (energy.canReceive(side) || energy.canExtract(side));
        }

        @Override
        public int receiveEnergy(net.minecraftforge.common.util.ForgeDirection from, int amount, boolean simulate) {
            HostEnergy energy = slots().energy;
            return energy == null ? 0 : energy.receive(Energy.side(from), amount, simulate);
        }

        @Override
        public int extractEnergy(net.minecraftforge.common.util.ForgeDirection from, int amount, boolean simulate) {
            HostEnergy energy = slots().energy;
            return energy == null ? 0 : energy.extract(Energy.side(from), amount, simulate);
        }

        @Override
        public int getEnergyStored(net.minecraftforge.common.util.ForgeDirection from) {
            HostEnergy energy = slots().energy;
            return energy == null ? 0 : energy.stored(Energy.side(from));
        }

        @Override
        public int getMaxEnergyStored(net.minecraftforge.common.util.ForgeDirection from) {
            HostEnergy energy = slots().energy;
            return energy == null ? 0 : energy.capacity(Energy.side(from));
        }
'''

EU = '''
        // IC2 EU (a sink and a source in IC2's energy net): 1 EU is 4 Forge Energy.

        @Override
        public boolean acceptsEnergyFrom(TileEntity emitter, net.minecraftforge.common.util.ForgeDirection direction) {
            HostEnergy energy = slots().energy;
            return energy != null && energy.canReceive(Energy.side(direction));
        }

        @Override
        public boolean emitsEnergyTo(TileEntity receiver, net.minecraftforge.common.util.ForgeDirection direction) {
            HostEnergy energy = slots().energy;
            return energy != null && energy.canExtract(Energy.side(direction));
        }

        @Override
        public double getDemandedEnergy() {
            HostEnergy energy = slots().energy;
            return energy == null ? 0 : energy.receive(-1, Integer.MAX_VALUE, true) / (double) Energy.FE_PER_EU;
        }

        @Override
        public int getSinkTier() {
            return Integer.MAX_VALUE; // takes any voltage: 1.20.1 machines don't explode
        }

        @Override
        public double injectEnergy(net.minecraftforge.common.util.ForgeDirection from, double amount, double voltage) {
            HostEnergy energy = slots().energy;
            if (energy == null) {
                return amount;
            }
            int accepted = energy.receive(Energy.side(from), (int) Math.min(Integer.MAX_VALUE, amount * Energy.FE_PER_EU), false);
            return Math.max(0, amount - accepted / (double) Energy.FE_PER_EU);
        }

        @Override
        public double getOfferedEnergy() {
            HostEnergy energy = slots().energy;
            if (energy == null) {
                return 0;
            }
            // all of it, at low voltage: IC2's net is a circuit, the amount sets the current (cables carry what
            // they can), the tier the voltage (which decides what explodes), so every IC2 machine still takes it
            return energy.extract(-1, Integer.MAX_VALUE, true) / (double) Energy.FE_PER_EU;
        }

        @Override
        public void drawEnergy(double amount) {
            HostEnergy energy = slots().energy;
            if (energy != null) {
                energy.extract(-1, (int) Math.ceil(amount * Energy.FE_PER_EU), false);
            }
        }

        @Override
        public int getSourceTier() {
            return Energy.EU_SOURCE_TIER;
        }
'''

FLUIDS = '''
        // Forge fluids (IFluidHandler): the host block's tanks.

        @Override
        public int fill(net.minecraftforge.common.util.ForgeDirection from, net.minecraftforge.fluids.FluidStack resource, boolean doFill) {
            HostFluids fluids = slots().fluids;
            if (fluids == null || resource == null || resource.getFluid() == null) {
                return 0;
            }
            return fluids.fill(Energy.side(from), resource.getFluid().getName(), resource.amount, !doFill);
        }

        @Override
        public net.minecraftforge.fluids.FluidStack drain(net.minecraftforge.common.util.ForgeDirection from, net.minecraftforge.fluids.FluidStack resource, boolean doDrain) {
            HostFluids fluids = slots().fluids;
            if (fluids == null || resource == null || resource.getFluid() == null) {
                return null;
            }
            return HostTiles.fluidStack(fluids.drain(Energy.side(from), resource.getFluid().getName(), resource.amount, !doDrain));
        }

        @Override
        public net.minecraftforge.fluids.FluidStack drain(net.minecraftforge.common.util.ForgeDirection from, int maxDrain, boolean doDrain) {
            HostFluids fluids = slots().fluids;
            return fluids == null ? null : HostTiles.fluidStack(fluids.drain(Energy.side(from), null, maxDrain, !doDrain));
        }

        @Override
        public boolean canFill(net.minecraftforge.common.util.ForgeDirection from, net.minecraftforge.fluids.Fluid fluid) {
            HostFluids fluids = slots().fluids;
            return fluids != null && fluid != null && fluids.fill(Energy.side(from), fluid.getName(), 1, true) > 0;
        }

        @Override
        public boolean canDrain(net.minecraftforge.common.util.ForgeDirection from, net.minecraftforge.fluids.Fluid fluid) {
            HostFluids fluids = slots().fluids;
            return fluids != null && fluid != null && fluids.drain(Energy.side(from), fluid.getName(), 1, true) != null;
        }

        @Override
        public net.minecraftforge.fluids.FluidTankInfo[] getTankInfo(net.minecraftforge.common.util.ForgeDirection from) {
            HostFluids fluids = slots().fluids;
            if (fluids == null) {
                return new net.minecraftforge.fluids.FluidTankInfo[0];
            }
            Object[][] tanks = fluids.tanks(Energy.side(from));
            net.minecraftforge.fluids.FluidTankInfo[] info = new net.minecraftforge.fluids.FluidTankInfo[tanks.length];
            for (int i = 0; i < tanks.length; i++) {
                info[i] = new net.minecraftforge.fluids.FluidTankInfo(HostTiles.fluidStack(new Object[]{tanks[i][0], tanks[i][1]}), (Integer) tanks[i][2]);
            }
            return info;
        }
'''

kinds = [
    # class name, superclass, extra overrides (energy kinds: below)
    ('Chest', 'net.minecraft.tileentity.TileEntityChest', '''
        @Override
        public void func_145979_i() { // checkForAdjacentChests: the host inventory is already both halves
            field_145984_a = true;
        }

        @Override
        public void func_145836_u() { // updateContainingBlockInfo
        }

        @Override
        public int func_145980_j() { // getChestType
            return 0;
        }
'''),
    ('Furnace', 'net.minecraft.tileentity.TileEntityFurnace', ''),
    ('Hopper', 'net.minecraft.tileentity.TileEntityHopper', ''),
    ('Dispenser', 'net.minecraft.tileentity.TileEntityDispenser', ''),
    ('Dropper', 'net.minecraft.tileentity.TileEntityDropper', ''),
    ('Brewing', 'net.minecraft.tileentity.TileEntityBrewingStand', ''),
    ('Generic', 'TileEntity', FLUIDS),
]

# 1.20.1 blocks with Forge Energy (and maybe an inventory): the energy APIs the installed mods ship.
energy_kinds = [
    ('EnergyRF', 'cofh.api.energy.IEnergyHandler', RF),
    ('EnergyEU', 'ic2.api.energy.tile.IEnergySink, ic2.api.energy.tile.IEnergySource', EU),
    ('EnergyRFEU', 'cofh.api.energy.IEnergyHandler, ic2.api.energy.tile.IEnergySink, ic2.api.energy.tile.IEnergySource', RF + EU),
]

delegates = '''
        private final Slots slots = new Slots();

        @Override
        public Slots slots() {
            return slots;
        }

        @Override
        public void func_145845_h() { // updateEntity: the host block entity ticks itself
        }

        @Override
        public boolean canUpdate() {
            return false;
        }

        @Override
        public int func_70302_i_() {
            return slots.size();
        }

        @Override
        public ItemStack func_70301_a(int slot) {
            return slots.get(slot);
        }

        @Override
        public ItemStack func_70298_a(int slot, int count) {
            return slots.decrease(slot, count);
        }

        @Override
        public ItemStack func_70304_b(int slot) {
            return null;
        }

        @Override
        public void func_70299_a(int slot, ItemStack stack) {
            slots.set(slot, stack);
        }

        @Override
        public String func_145825_b() {
            return slots.name();
        }

        @Override
        public boolean func_145818_k_() {
            return false;
        }

        @Override
        public int func_70297_j_() {
            return slots.limit();
        }

        @Override
        public void func_70296_d() {
            slots.flush();
            slots.changed();
        }

        @Override
        public boolean func_70300_a(EntityPlayer player) {
            return false;
        }

        @Override
        public void func_70295_k_() {
        }

        @Override
        public void func_70305_f() {
        }

        @Override
        public boolean func_94041_b(int slot, ItemStack stack) {
            return slots.canPlace(slot, stack);
        }

        @Override
        public int[] func_94128_d(int side) {
            return slots.sides(side);
        }

        @Override
        public boolean func_102007_a(int slot, ItemStack stack, int side) {
            return slots.canInsert(slot, stack, side);
        }

        @Override
        public boolean func_102008_b(int slot, ItemStack stack, int side) {
            return slots.canExtract(slot, stack, side);
        }

        @Override
        public void func_145839_a(NBTTagCompound tag) { // readFromNBT: never saved
        }

        @Override
        public void func_145841_b(NBTTagCompound tag) { // writeToNBT
        }
'''

out = []
out.append('''package retrofit.legacy.bridge;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.ISidedInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import retrofit.api.HostEnergy;
import retrofit.api.HostFluids;
import retrofit.api.HostInventory;
import retrofit.api.HostWorld;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Tile entities standing in for the host level's own inventories (vanilla chests, furnaces,
 * hoppers, dispensers, brewing stands, and any 1.20.1 block with an item handler), so legacy
 * pipes, machines and tools that look for an IInventory next to them find one. Each is the
 * matching 1.7.10 class (mods check instanceof TileEntityChest, TileEntityFurnace...) with its
 * inventory methods going to the host's.
 *
 * 1.20.1 blocks with Forge Energy stand in as CoFH RF handlers and as IC2 energy sinks and
 * sources (registered in IC2's energy net), whichever of those APIs the installed mods ship.
 *
 * Mods often change a stack they got from getStackInSlot in place (stackSize -= n) and then call
 * markDirty, or not at all: stacks handed out are remembered, and any change is written to the
 * host on the next access, on markDirty, and at the end of every server tick.
 *
 * GENERATED by tools/gen_host_tiles.py.
 */
final class HostTiles {
    private static final Map<World, Map<Long, TileEntity>> PROXIES = new WeakHashMap<World, Map<Long, TileEntity>>();

    private HostTiles() {
    }

    /** The stand-in at a position, or null if the host has no inventory or energy there. */
    static synchronized TileEntity at(World world, HostWorld host, int x, int y, int z) {
        HostInventory inventory = host.getHostInventory(x, y, z);
        HostEnergy energy = host.getHostEnergy(x, y, z);
        HostFluids fluids = host.getHostFluids(x, y, z);
        Map<Long, TileEntity> proxies = PROXIES.get(world);
        long key = ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | (y & 0xFFFL);
        TileEntity proxy = proxies == null ? null : proxies.get(key);
        if (inventory == null && energy == null && fluids == null) {
            if (proxy != null) {
                retire(proxy);
                proxies.remove(key);
            }
            return null;
        }
        Block block = (Block) host.getLegacyBlock(x, y, z);
        String kind = kind(block == null ? null : Block.field_149771_c.func_148750_c(block)); // blockRegistry.getNameForObject
        if (energy != null && kind.equals("Generic")) {
            kind = Energy.proxyKind();
        }
        if (proxy == null || !((Proxy) proxy).kind().equals(kind)) {
            if (proxy != null) {
                retire(proxy);
            }
            proxy = create(kind);
            proxy.func_145834_a(world); // setWorldObj
            proxy.field_145851_c = x;
            proxy.field_145848_d = y;
            proxy.field_145849_e = z;
            if (proxies == null) {
                proxies = new HashMap<Long, TileEntity>();
                PROXIES.put(world, proxies);
            }
            proxies.put(key, proxy);
            ((Proxy) proxy).slots().use(inventory, energy, fluids);
            if (kind.contains("EU") && !world.field_72995_K) { // isRemote
                Energy.joinEnergyNet(proxy);
            }
        }
        ((Proxy) proxy).slots().use(inventory, energy, fluids);
        return proxy;
    }

    /** A stand-in whose host block went away or changed: written back, invalidated, out of IC2's net. */
    private static void retire(TileEntity proxy) {
        ((Proxy) proxy).slots().flush();
        proxy.func_145843_s(); // invalidate: mods holding it let go
        if (((Proxy) proxy).kind().contains("EU")) {
            Energy.leaveEnergyNet(proxy);
        }
    }

    private static int ticks;

    /**
     * End of every server tick: changes made to handed-out stacks reach the host. Every 2 seconds,
     * stand-ins whose host block went away (nobody looked there since) are retired, so mods and
     * IC2's energy net that still hold them let go.
     */
    static synchronized void flushAll() {
        boolean revalidate = ++ticks % 40 == 0;
        for (Map.Entry<World, Map<Long, TileEntity>> entry : new ArrayList<Map.Entry<World, Map<Long, TileEntity>>>(PROXIES.entrySet())) {
            for (TileEntity proxy : new ArrayList<TileEntity>(entry.getValue().values())) {
                ((Proxy) proxy).slots().flush();
                HostWorld host = revalidate ? WorldGlue.hostOf(entry.getKey()) : null;
                if (host != null) {
                    at(entry.getKey(), host, proxy.field_145851_c, proxy.field_145848_d, proxy.field_145849_e);
                }
            }
        }
        Energy.flushEnergyNet();
    }

    /** A legacy FluidStack for {legacy fluid name, amount}, or null. */
    static net.minecraftforge.fluids.FluidStack fluidStack(Object[] fluid) {
        if (fluid == null || fluid[0] == null || ((Integer) fluid[1]) <= 0) {
            return null;
        }
        net.minecraftforge.fluids.Fluid type = net.minecraftforge.fluids.FluidRegistry.getFluid((String) fluid[0]);
        return type == null ? null : new net.minecraftforge.fluids.FluidStack(type, (Integer) fluid[1]);
    }

    private static String kind(String blockName) {
        if (blockName == null) {
            return "Generic";
        }
        if (blockName.equals("minecraft:chest") || blockName.equals("minecraft:trapped_chest")) {
            return "Chest";
        }
        if (blockName.equals("minecraft:furnace") || blockName.equals("minecraft:lit_furnace")) {
            return "Furnace";
        }
        if (blockName.equals("minecraft:hopper")) {
            return "Hopper";
        }
        if (blockName.equals("minecraft:dispenser")) {
            return "Dispenser";
        }
        if (blockName.equals("minecraft:dropper")) {
            return "Dropper";
        }
        if (blockName.equals("minecraft:brewing_stand")) {
            return "Brewing";
        }
        return "Generic";
    }

    private static TileEntity create(String kind) {
''')
for name, sup, extra in kinds:
    out.append('        if (kind.equals("%s")) {\n            return new %s();\n        }\n' % (name, name))
out.append('        if (kind.startsWith("Energy")) {\n')
out.append('            // By name: they implement mods\' energy APIs, and naming them here would load them without the APIs.\n')
out.append('            try {\n')
out.append('                return (TileEntity) Class.forName(HostTiles.class.getName() + "$" + kind).newInstance();\n')
out.append('            } catch (ReflectiveOperationException e) {\n')
out.append('                return new Generic();\n')
out.append('            }\n')
out.append('        }\n')
out.append('''        return new Generic();
    }

    interface Proxy {
        Slots slots();

        String kind();
    }

    /** The host inventory and the legacy stacks handed out from it. */
    static final class Slots {
        HostEnergy energy;
        HostFluids fluids;
        private HostInventory inventory;
        private ItemStack[] given = new ItemStack[0];
        private ItemStack[] snapshot = new ItemStack[0];

        void use(HostInventory next, HostEnergy nextEnergy, HostFluids nextFluids) {
            energy = nextEnergy;
            fluids = nextFluids;
            if (inventory != next) {
                flush();
                inventory = next;
                given = new ItemStack[0];
                snapshot = new ItemStack[0];
            }
        }

        private void track(int slot, ItemStack stack) {
            if (slot >= given.length) {
                int length = Math.max(slot + 1, inventory == null ? 0 : inventory.size());
                given = java.util.Arrays.copyOf(given, length);
                snapshot = java.util.Arrays.copyOf(snapshot, length);
            }
            given[slot] = stack;
            snapshot[slot] = stack == null ? null : stack.func_77946_l(); // copy
        }

        private void flush(int slot) {
            if (inventory == null || slot >= given.length || given[slot] == null) {
                return;
            }
            ItemStack stack = given[slot];
            if (!ItemStack.func_77989_b(stack, snapshot[slot])) { // areItemStacksEqual (item, damage, size, tag)
                inventory.setStack(slot, stack.field_77994_a <= 0 ? null : stack); // stackSize
                if (stack.field_77994_a <= 0) {
                    given[slot] = null;
                    snapshot[slot] = null;
                } else {
                    snapshot[slot] = stack.func_77946_l();
                }
            }
        }

        void flush() {
            for (int slot = 0; slot < given.length; slot++) {
                try {
                    flush(slot);
                } catch (RuntimeException e) {
                    given[slot] = null; // the host inventory went away
                }
            }
        }

        int size() {
            return inventory == null ? 0 : inventory.size();
        }

        ItemStack get(int slot) {
            if (inventory == null || slot < 0 || slot >= inventory.size()) {
                return null;
            }
            flush(slot);
            ItemStack stack = (ItemStack) inventory.getStack(slot);
            track(slot, stack);
            return stack;
        }

        ItemStack decrease(int slot, int count) {
            ItemStack stack = get(slot);
            if (stack == null || count <= 0) {
                return null;
            }
            if (stack.field_77994_a <= count) {
                inventory.setStack(slot, null);
                track(slot, null);
                inventory.changed();
                return stack;
            }
            ItemStack taken = stack.func_77979_a(count); // splitStack
            inventory.setStack(slot, stack);
            track(slot, stack);
            inventory.changed();
            return taken;
        }

        void set(int slot, ItemStack stack) {
            if (inventory == null || slot < 0 || slot >= inventory.size()) {
                return;
            }
            inventory.setStack(slot, stack == null || stack.field_77994_a <= 0 ? null : stack);
            track(slot, stack);
            inventory.changed();
        }

        String name() {
            return inventory == null ? "container" : inventory.name();
        }

        int limit() {
            return inventory == null ? 64 : inventory.stackLimit();
        }

        void changed() {
            if (inventory != null) {
                inventory.changed();
            }
        }

        boolean canPlace(int slot, ItemStack stack) {
            return inventory != null && stack != null && inventory.canPlace(slot, stack);
        }

        int[] sides(int side) {
            return inventory == null ? new int[0] : inventory.slotsForSide(side);
        }

        boolean canInsert(int slot, ItemStack stack, int side) {
            return inventory != null && stack != null && inventory.canInsert(slot, stack, side);
        }

        boolean canExtract(int slot, ItemStack stack, int side) {
            flush(slot);
            return inventory != null && inventory.canExtract(slot, stack, side);
        }
    }
''')
for name, sup, extra in kinds:
    out.append('''
    static %sclass %s extends %s implements ISidedInventory, Proxy%s {
        @Override
        public String kind() {
            return "%s";
        }
%s%s    }
''' % ('' if name == 'Generic' else 'final ', name, sup, ', net.minecraftforge.fluids.IFluidHandler' if name == 'Generic' else '', name, delegates, extra))
for name, interfaces, methods in energy_kinds:
    out.append('''
    /** Loaded only when the installed mods ship these APIs (see Energy.proxyKind). */
    static final class %s extends Generic implements %s {
        @Override
        public String kind() {
            return "%s";
        }
%s    }
''' % (name, interfaces, name, methods))
out.append('}\n')
open('legacy-bridge/src/main/java/retrofit/legacy/bridge/HostTiles.java', 'w', encoding='utf-8').write(''.join(out))
print('ok')
