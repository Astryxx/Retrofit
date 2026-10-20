package retrofit.legacy.bridge;

import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;

import java.util.ArrayList;
import java.util.List;

/**
 * Energy between legacy mods and 1.20.1 mods. Legacy machines speak CoFH's RF (BuildCraft,
 * Thermal Expansion, EnderIO...) or IC2's EU; 1.20.1 machines speak Forge Energy (FE).
 *
 * - Legacy tile entities offer their energy to 1.20.1 blocks as Forge Energy (the host's
 *   LegacyBlockEntity asks here): RF handlers 1:1, IC2 sinks and sources at 1 EU = 4 FE.
 * - 1.20.1 blocks with Forge Energy appear to legacy code as RF handlers, and join IC2's energy
 *   net as a sink and a source (HostTiles' energy stand-ins).
 *
 * Neither API is part of 1.7.10: mods ship them. The classes that use them load only when the
 * API is there (RfAccess, EuAccess, the stand-ins); this class works without either.
 */
public final class Energy {
    /** The usual rate between IC2 and RF/FE mods. */
    static final int FE_PER_EU = 4;
    /** Stand-ins feed IC2's net at low voltage (32 EU per tick), which every IC2 machine takes. */
    static final int EU_SOURCE_TIER = 1;
    static final double EU_SOURCE_PACKET = 32;

    private static Boolean rf;
    private static Boolean eu;
    private static final List<TileEntity> JOINING = new ArrayList<TileEntity>();
    private static final List<TileEntity> LEAVING = new ArrayList<TileEntity>();

    private Energy() {
    }

    private static boolean present(String className) {
        try {
            Class.forName(className, false, Energy.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean rf() {
        if (rf == null) {
            rf = present("cofh.api.energy.IEnergyHandler") && present("cofh.api.energy.IEnergyReceiver");
        }
        return rf;
    }

    static boolean eu() {
        if (eu == null) {
            eu = present("ic2.api.energy.tile.IEnergySink") && present("ic2.api.energy.event.EnergyTileLoadEvent");
        }
        return eu;
    }

    static int side(ForgeDirection direction) {
        return direction == null || direction == ForgeDirection.UNKNOWN ? -1 : direction.ordinal();
    }

    static ForgeDirection direction(int side) {
        return side < 0 || side > 5 ? ForgeDirection.UNKNOWN : ForgeDirection.getOrientation(side);
    }

    /** Which energy stand-in a 1.20.1 block with Forge Energy gets: by the APIs installed. */
    static String proxyKind() {
        boolean withRf = rf();
        boolean withEu = eu();
        return withRf && withEu ? "EnergyRFEU" : withRf ? "EnergyRF" : withEu ? "EnergyEU" : "Generic";
    }

    // ---- IC2's energy net: stand-ins join and leave between ticks (never while the net is working) ----

    static void joinEnergyNet(TileEntity proxy) {
        synchronized (JOINING) {
            JOINING.add(proxy);
        }
    }

    static void leaveEnergyNet(TileEntity proxy) {
        synchronized (JOINING) {
            if (!JOINING.remove(proxy)) {
                LEAVING.add(proxy);
            }
        }
    }

    /** End of the server tick (HostTiles.flushAll). */
    static void flushEnergyNet() {
        List<TileEntity> joining;
        List<TileEntity> leaving;
        synchronized (JOINING) {
            if (JOINING.isEmpty() && LEAVING.isEmpty()) {
                return;
            }
            joining = new ArrayList<TileEntity>(JOINING);
            leaving = new ArrayList<TileEntity>(LEAVING);
            JOINING.clear();
            LEAVING.clear();
        }
        for (TileEntity proxy : leaving) {
            try {
                EuAccess.leave(proxy);
            } catch (Throwable t) {
                System.out.println("[Retrofit] IC2's energy net refused to let go of a 1.20.1 block: " + t);
            }
        }
        for (TileEntity proxy : joining) {
            try {
                EuAccess.join(proxy);
            } catch (Throwable t) {
                System.out.println("[Retrofit] A 1.20.1 block could not join IC2's energy net: " + t);
            }
        }
    }

    /** Self-test (the mod sweep): a 1.20.1 block with Forge Energy at a position, as legacy code sees it. */
    public static String checkHostEnergy(Object legacyWorld, int x, int y, int z) {
        TileEntity tile = ((net.minecraft.world.World) legacyWorld).func_147438_o(x, y, z); // getTileEntity
        if (tile == null) {
            return "FAILED: no tile entity";
        }
        StringBuilder result = new StringBuilder(tile.getClass().getSimpleName());
        if (rf()) {
            int taken = RfAccess.receive(tile, ForgeDirection.UP, 500, false);
            result.append(taken == 500 ? "; RF receiver took 500: OK" : "; RF receiver took " + taken + ": FAILED");
        }
        if (eu()) {
            result.append(EuAccess.kind(tile, ForgeDirection.UP) == 3 ? "; IC2 sink and source: OK" : "; not an IC2 sink and source: FAILED");
        }
        return result.toString();
    }

    // ---- Legacy items as Forge Energy (1.20.1 chargers, batteries in 1.20.1 machines) ----

    /** 1 can be charged, 2 can be drawn from; 0 not an energy item. */
    public static int itemEnergyKind(Object legacyStack) {
        int kind = 0;
        if (rf()) {
            kind |= RfAccess.itemKind(legacyStack);
        }
        if (kind == 0 && eu()) {
            kind |= EuAccess.itemKind(legacyStack);
        }
        return kind;
    }

    /** Charges the stack (changing it): the Forge Energy taken. */
    public static int itemEnergyReceive(Object legacyStack, int amount, boolean simulate) {
        if (rf() && RfAccess.itemKind(legacyStack) != 0) {
            return RfAccess.itemReceive(legacyStack, amount, simulate);
        }
        return eu() && EuAccess.itemKind(legacyStack) != 0 ? EuAccess.itemReceive(legacyStack, amount, simulate) : 0;
    }

    public static int itemEnergyExtract(Object legacyStack, int amount, boolean simulate) {
        if (rf() && RfAccess.itemKind(legacyStack) != 0) {
            return RfAccess.itemExtract(legacyStack, amount, simulate);
        }
        return eu() && (EuAccess.itemKind(legacyStack) & 2) != 0 ? EuAccess.itemExtract(legacyStack, amount, simulate) : 0;
    }

    /** {stored, capacity} in Forge Energy. */
    public static int[] itemEnergyStorage(Object legacyStack) {
        if (rf() && RfAccess.itemKind(legacyStack) != 0) {
            return RfAccess.itemStorage(legacyStack);
        }
        return eu() && EuAccess.itemKind(legacyStack) != 0 ? EuAccess.itemStorage(legacyStack) : new int[]{0, 0};
    }

    // ---- Legacy tile entities as Forge Energy (the host asks) ----

    /** What a legacy tile entity does with energy on a side (-1: any): 1 takes it in, 2 gives it out; 0 neither. */
    public static int legacyEnergyKind(Object tile, int side) {
        int kind = 0;
        if (rf()) {
            kind |= RfAccess.kind(tile, direction(side));
        }
        if (eu()) {
            kind |= EuAccess.kind(tile, direction(side));
        }
        return kind;
    }

    public static int legacyEnergyReceive(Object tile, int side, int amount, boolean simulate) {
        int taken = 0;
        if (rf()) {
            taken = RfAccess.receive(tile, direction(side), amount, simulate);
        }
        if (taken == 0 && eu()) {
            taken = EuAccess.receive(tile, direction(side), amount, simulate);
        }
        return taken;
    }

    public static int legacyEnergyExtract(Object tile, int side, int amount, boolean simulate) {
        int given = 0;
        if (rf()) {
            given = RfAccess.extract(tile, direction(side), amount, simulate);
        }
        if (given == 0 && eu()) {
            given = EuAccess.extract(tile, direction(side), amount, simulate);
        }
        return given;
    }

    /** {stored, capacity} in Forge Energy. */
    public static int[] legacyEnergyStorage(Object tile, int side) {
        if (rf()) {
            int[] storage = RfAccess.storage(tile, direction(side));
            if (storage != null) {
                return storage;
            }
        }
        if (eu()) {
            int[] storage = EuAccess.storage(tile);
            if (storage != null) {
                return storage;
            }
        }
        return new int[]{0, 0};
    }
}
