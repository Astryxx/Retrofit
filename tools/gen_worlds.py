"""Generates the three legacy world classes (BridgeWorld, BridgeWorldServer, BridgeWorldClient)
from one list of bridged methods, so they can't drift apart. Run from the repository root:

    python -I tools/gen_worlds.py
"""
import pathlib

OUT = pathlib.Path('legacy-bridge/src/main/java/retrofit/legacy/bridge')

# (javadoc, signature, body) - SRG names; MCP names in the javadoc.
METHODS = [
    ("getBlock", "public Block func_147439_a(int x, int y, int z)", "return WorldGlue.getBlock(host, x, y, z);"),
    ("getBlockMetadata", "public int func_72805_g(int x, int y, int z)", "return WorldGlue.getMeta(host, x, y, z);"),
    ("setBlock(x, y, z, block, meta, flags)", "public boolean func_147465_d(int x, int y, int z, Block block, int meta, int flags)",
     "return WorldGlue.setBlock(host, x, y, z, block, meta, flags);"),
    ("setBlock(x, y, z, block)", "public boolean func_147449_b(int x, int y, int z, Block block)",
     "return WorldGlue.setBlock(host, x, y, z, block, 0, 3);"),
    ("setBlockToAir", "public boolean func_147468_f(int x, int y, int z)",
     "return WorldGlue.setBlock(host, x, y, z, Blocks.field_150350_a, 0, 3);"),
    ("setBlockMetadataWithNotify", "public boolean func_72921_c(int x, int y, int z, int meta, int flags)",
     "return WorldGlue.setMeta(host, x, y, z, meta, flags);"),
    ("getTileEntity", "public TileEntity func_147438_o(int x, int y, int z)", "return WorldGlue.getTileEntity(host, x, y, z);"),
    ("setTileEntity", "public void func_147455_a(int x, int y, int z, TileEntity tile)",
     "WorldGlue.setTileEntity(host, this, x, y, z, tile);"),
    ("removeTileEntity", "public void func_147475_p(int x, int y, int z)",
     "if (host != null) {\n            host.removeLegacyTileEntity(x, y, z);\n        }"),
    ("markBlockForUpdate", "public void func_147471_g(int x, int y, int z)",
     "if (host != null) {\n            host.markBlockForUpdate(x, y, z);\n        }"),
    ("markTileEntityChunkModified", "public void func_147476_b(int x, int y, int z, TileEntity tile)",
     "if (host != null) {\n            host.markTileEntityChanged(x, y, z);\n        }"),
    ("markBlockRangeForRenderUpdate", "public void func_147458_c(int x1, int y1, int z1, int x2, int y2, int z2)",
     "if (host != null) {\n            host.markBlockRangeForRenderUpdate(x1, y1, z1, x2, y2, z2);\n        }"),
    ("scheduleBlockUpdate", "public void func_147464_a(int x, int y, int z, Block block, int delay)",
     "if (host != null) {\n            host.scheduleBlockUpdate(x, y, z, block, delay, 0);\n        }"),
    ("scheduleBlockUpdateWithPriority", "public void func_147454_a(int x, int y, int z, Block block, int delay, int priority)",
     "if (host != null) {\n            host.scheduleBlockUpdate(x, y, z, block, delay, priority);\n        }"),
    ("notifyBlocksOfNeighborChange", "public void func_147459_d(int x, int y, int z, Block block)",
     "if (host != null) {\n            host.notifyNeighbors(x, y, z, block);\n        }"),
    ("notifyBlockOfNeighborChange", "public void func_147460_e(int x, int y, int z, Block neighbor)",
     "if (host != null) {\n            host.notifyNeighbor(x, y, z, neighbor);\n        }"),
    ("getIndirectPowerLevelTo", "public int func_72878_l(int x, int y, int z, int side)",
     "return host == null ? 0 : host.getIndirectPower(x, y, z, side);"),
    ("isBlockProvidingPowerTo (strong power)", "public int func_72879_k(int x, int y, int z, int side)",
     "return host == null ? 0 : host.getIndirectPower(x, y, z, side);"),
    ("getBlockPowerInput", "public int func_94577_B(int x, int y, int z)",
     "return host == null ? 0 : host.getDirectPowerInput(x, y, z);"),
    ("getStrongestIndirectPower", "public int func_94572_D(int x, int y, int z)",
     "return host == null ? 0 : host.getStrongestIndirectPower(x, y, z);"),
    ("isBlockIndirectlyGettingPowered", "public boolean func_72864_z(int x, int y, int z)",
     "return host != null && host.getStrongestIndirectPower(x, y, z) > 0;"),
    ("getBlockLightValue", "public int func_72957_l(int x, int y, int z)",
     "return host == null ? 15 : Math.max(host.getBlockLight(x, y, z), host.getSkyLight(x, y, z));"),
    ("getFullBlockLightValue", "public int func_72883_k(int x, int y, int z)",
     "return host == null ? 15 : Math.max(host.getBlockLight(x, y, z), host.getSkyLight(x, y, z));"),
    ("getSavedLightValue", "public int func_72972_b(EnumSkyBlock type, int x, int y, int z)",
     "if (host == null) {\n            return 15;\n        }\n        return type == EnumSkyBlock.Sky ? host.getSkyLight(x, y, z) : host.getBlockLight(x, y, z);"),
    ("getLightBrightnessForSkyBlocks", "public int func_72802_i(int x, int y, int z, int minBlockLight)",
     "int packed = host == null ? 0xF000F0 : host.getPackedLight(x, y, z);\n        return ((packed >> 4) & 15) < minBlockLight ? (packed & ~0xF0) | (minBlockLight << 4) : packed;"),
    ("canBlockSeeTheSky", "public boolean func_72937_j(int x, int y, int z)", "return host == null || host.canSeeSky(x, y, z);"),
    ("getHeightValue", "public int func_72976_f(int x, int z)", "return host == null ? 64 : host.getHeight(x, z);"),
    ("getTopSolidOrLiquidBlock", "public int func_72825_h(int x, int z)", "return host == null ? 64 : host.getHeight(x, z);"),
    ("playSoundEffect", "public void func_72908_a(double x, double y, double z, String name, float volume, float pitch)",
     "if (host != null) {\n            host.playSound(x, y, z, name, volume, pitch);\n        }"),
    ("playSoundAtEntity", "public void func_72956_a(Entity entity, String name, float volume, float pitch)",
     "if (host != null && entity != null) {\n            host.playSound(entity.field_70165_t, entity.field_70163_u - entity.field_70129_M, entity.field_70161_v, name, volume, pitch);\n        }"),
    ("playSound (client)", "public void func_72980_b(double x, double y, double z, String name, float volume, float pitch, boolean distanceDelay)",
     "if (host != null) {\n            host.playSound(x, y, z, name, volume, pitch);\n        }"),
    ("spawnParticle", "public void func_72869_a(String name, double x, double y, double z, double vx, double vy, double vz)",
     "if (host != null) {\n            host.spawnParticle(name, x, y, z, vx, vy, vz);\n        }"),
    ("spawnEntityInWorld", "public boolean func_72838_d(Entity entity)", "return WorldGlue.spawnEntity(host, entity);"),
    ("getCollidingBoundingBoxes", "public List func_72945_a(Entity entity, AxisAlignedBB box)",
     "return WorldGlue.collisionBoxes(host, this, entity, box);"),
    ("getEntitiesWithinAABBExcludingEntity", "public List func_72839_b(Entity exclude, AxisAlignedBB box)",
     "return WorldGlue.entitiesExcluding(this, exclude, box, null);"),
    ("getEntitiesWithinAABB", "public List func_72872_a(Class type, AxisAlignedBB box)",
     "return WorldGlue.selectEntities(this, type, box, null);"),
    ("selectEntitiesWithinAABB", "public List func_82733_a(Class type, AxisAlignedBB box, IEntitySelector selector)",
     "return WorldGlue.selectEntities(this, type, box, selector);"),
    ("setEntityState", "public void func_72960_a(Entity entity, byte event)",
     "if (host != null && entity != null) {\n            host.entityEvent(entity, event);\n        }"),
    ("newExplosion", "public net.minecraft.world.Explosion func_72885_a(Entity exploder, double x, double y, double z, float strength, boolean fire, boolean smoke)",
     "if (host != null) {\n            host.explode(exploder, x, y, z, strength, fire, smoke);\n        }\n        net.minecraft.world.Explosion explosion = new net.minecraft.world.Explosion(this, exploder, x, y, z, strength);\n        explosion.field_77286_a = fire;\n        explosion.field_82755_b = smoke;\n        return explosion;"),
    ("addWeatherEffect", "public boolean func_72942_c(Entity entity)",
     "if (host != null && entity != null) {\n            host.lightning(entity.field_70165_t, entity.field_70163_u, entity.field_70161_v);\n        }\n        return true;"),
    ("getBiomeGenForCoords", "public BiomeGenBase func_72807_a(int x, int z)", "return WorldGlue.biome(host, x, z);"),
    ("getSeed", "public long func_72905_C()", "return host == null ? 0 : host.getSeed();"),
    ("getWorldTime", "public long func_72820_D()", "return host == null ? 0 : host.getDayTime();"),
    ("getTotalWorldTime", "public long func_82737_E()", "return host == null ? 0 : host.getTotalWorldTime();"),
    ("isRaining", "public boolean func_72896_J()", "return host != null && host.isRaining();"),
    ("isThundering", "public boolean func_72911_I()", "return host != null && host.isThundering();"),
    ("createChunkProvider", "protected IChunkProvider func_72970_h()", "return new BridgeChunkProvider(this);"),
    ("getRenderDistanceChunks", "protected int func_152379_p()", "return 8;"),
]

IMPORTS = """import net.minecraft.block.Block;
import net.minecraft.command.IEntitySelector;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.chunk.IChunkProvider;
import retrofit.api.HostWorld;

import java.util.List;
"""

CLASSES = {
    "BridgeWorld": ("""/**
 * A plain 1.7.10 World backed by a host level (used for the isolated one-block world and other
 * worlds that are neither a server nor a client world). See {@link WorldGlue}.
 *
 * Generated by tools/gen_worlds.py; edit the generator, not this file.
 */
@SuppressWarnings({"rawtypes"})
public class BridgeWorld extends World {""", """    public BridgeWorld(HostWorld host, int dimension) {
        // The (saveHandler, name, settings, provider, profiler) constructor: the other one is client-only.
        super(BridgeSaveHandler.create(false), "Retrofit", LegacyWorlds.survivalSettings(),
                WorldProvider.func_76570_a(dimension), new Profiler()); // getProviderForDimension
        this.host = host;
        this.field_72995_K = host.isClientSide(); // isRemote
        this.field_73013_u = net.minecraft.world.EnumDifficulty.NORMAL; // difficultySetting, until the host's first tick
    }

    /** getEntityByID */
    @Override
    public Entity func_73045_a(int id) {
        return LegacyEntities.byId(this, id);
    }
""", "import net.minecraft.profiler.Profiler;\nimport net.minecraft.world.World;\nimport net.minecraft.world.WorldProvider;\n"),
    "BridgeWorldServer": ("""/**
 * The server side of a host level as a real 1.7.10 WorldServer, so mods' {@code instanceof
 * WorldServer} checks hold and server players can be real EntityPlayerMPs. See {@link WorldGlue}.
 *
 * Generated by tools/gen_worlds.py; edit the generator, not this file.
 */
@SuppressWarnings({"rawtypes"})
public class BridgeWorldServer extends WorldServer {""", """    public BridgeWorldServer(HostWorld host, int dimension) {
        super(LegacyWorlds.server(), BridgeSaveHandler.create(true), "Retrofit", dimension, LegacyWorlds.survivalSettings(), new Profiler());
        this.host = host;
        this.field_73013_u = net.minecraft.world.EnumDifficulty.NORMAL; // difficultySetting, until the host's first tick
    }

    /**
     * Forge asks for this from the World constructor, before the chunk provider exists. The host
     * saves the real world; legacy per-world data (map storage) goes to the Retrofit folder.
     */
    @Override
    public java.io.File getChunkSaveLocation() {
        return LegacyWorlds.dimensionDirectory(this.field_73011_w.field_76574_g); // provider.dimensionId
    }

    /** scheduleBlockUpdate from a save (WorldServer) */
    @Override
    public void func_147446_b(int x, int y, int z, Block block, int delay, int priority) {
        if (host != null) {
            host.scheduleBlockUpdate(x, y, z, block, delay, priority);
        }
    }
""", "import net.minecraft.profiler.Profiler;\nimport net.minecraft.world.WorldServer;\n"),
    "BridgeWorldClient": ("""/**
 * The client side of a host level as a real 1.7.10 WorldClient (Minecraft.theWorld), so client
 * code and renderers get the world type they expect. See {@link WorldGlue}.
 *
 * Generated by tools/gen_worlds.py; edit the generator, not this file.
 */
@SuppressWarnings({"rawtypes"})
public class BridgeWorldClient extends WorldClient {""", """    public BridgeWorldClient(HostWorld host, int dimension) {
        super(LegacyWorlds.clientNetHandler(), LegacyWorlds.survivalSettings(), dimension, EnumDifficulty.NORMAL, new Profiler());
        this.host = host;
    }

    /** getEntityByID */
    @Override
    public Entity func_73045_a(int id) {
        Entity entity = super.func_73045_a(id);
        return entity != null ? entity : LegacyEntities.byId(this, id);
    }
""", "import net.minecraft.client.multiplayer.WorldClient;\nimport net.minecraft.profiler.Profiler;\nimport net.minecraft.world.EnumDifficulty;\n"),
}

for name, (header, constructor, extra_imports) in CLASSES.items():
    lines = ["package retrofit.legacy.bridge;", "", extra_imports + IMPORTS, header, "    private HostWorld host;", "", constructor]
    lines.append("""    public HostWorld host() {
        return host;
    }

    /** World generation reuses one world per dimension, pointed at each chunk's region in turn (see WorldGen). */
    void setHost(HostWorld host) {
        this.host = host;
    }
""")
    for doc, signature, body in METHODS:
        lines.append(f"    /** {doc} */\n    @Override\n    {signature} {{\n        {body}\n    }}\n")
    lines.append("}\n")
    (OUT / f"{name}.java").write_text("\n".join(lines), encoding="utf-8")
    print("wrote", name)
