package retrofit.legacy.bridge;

import net.minecraft.block.Block;
import net.minecraft.command.IEntitySelector;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.item.EntityXPOrb;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.World;
import net.minecraft.world.biome.BiomeGenBase;
import retrofit.api.HostWorld;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The bridged World behaviour shared by {@link BridgeWorld}, {@link BridgeWorldServer} and
 * {@link BridgeWorldClient}: every method legacy code uses to read or change the world goes to
 * the host level through {@link HostWorld}. The world classes override the legacy methods and
 * call these (Java has one superclass, and each needs a different one: World, WorldServer,
 * WorldClient).
 *
 * The legacy World constructor calls some methods before the host is set; those fall back to
 * empty-world answers.
 */
final class WorldGlue {
    private static final Map<String, String> BIOME_ALIASES = new HashMap<String, String>();
    private static Map<String, BiomeGenBase> biomesByName;

    static {
        // 1.20.1 biome path -> 1.7.10 biome name, where they differ.
        String[][] aliases = {
                {"swamp", "swampland"}, {"nether_wastes", "hell"}, {"the_end", "sky"}, {"snowy_plains", "ice plains"},
                {"dark_forest", "roofed forest"}, {"badlands", "mesa"}, {"mushroom_fields", "mushroomisland"},
                {"windswept_hills", "extreme hills"}, {"sunflower_plains", "plains"}, {"meadow", "plains"},
                {"old_growth_pine_taiga", "mega taiga"}, {"snowy_taiga", "cold taiga"}, {"frozen_ocean", "frozenocean"},
                {"stony_shore", "stone beach"}, {"snowy_beach", "cold beach"}, {"wooded_badlands", "mesa plateau f"},
                {"savanna_plateau", "savanna plateau"}, {"sparse_jungle", "jungleedge"}, {"flower_forest", "flower forest"},
        };
        for (String[] alias : aliases) {
            BIOME_ALIASES.put(alias[0], alias[1]);
        }
    }

    private WorldGlue() {
    }

    static Block getBlock(HostWorld host, int x, int y, int z) {
        Object block = host == null ? null : host.getLegacyBlock(x, y, z);
        return block == null ? Blocks.field_150350_a : (Block) block; // air
    }

    static int getMeta(HostWorld host, int x, int y, int z) {
        return host == null ? 0 : host.getLegacyMeta(x, y, z);
    }

    static boolean setBlock(HostWorld host, int x, int y, int z, Block block, int meta, int flags) {
        if (host == null) {
            return false;
        }
        if (ModernContent.isStandIn(block)) {
            // A stand-in names a 1.20.1 block only with its tile entity (see setTileEntity), so put down bare it
            // clears the place for that tile entity, replacing what was there as any block did (Pistronics puts a
            // moved block down where its moving block was, then its tile entity, then the block again). The
            // block and metadata already there change nothing, as in 1.7.10's chunks: set again, the stand-in
            // must not clear the 1.20.1 block it stands for.
            if (getBlock(host, x, y, z) == block && getMeta(host, x, y, z) == meta) {
                return false;
            }
            return host.setLegacyBlock(x, y, z, net.minecraft.init.Blocks.field_150350_a, 0, flags); // air
        }
        return host.setLegacyBlock(x, y, z, block, meta, flags);
    }

    /** setBlockMetadataWithNotify: mods rotate and toggle blocks this way. */
    static boolean setMeta(HostWorld host, int x, int y, int z, int meta, int flags) {
        return host != null && host.setLegacyBlock(x, y, z, getBlock(host, x, y, z), meta, flags);
    }

    /** A bridged server world's PlayerManager answers from the host's players (see BridgePlayerManager). */
    static void installPlayerManager(net.minecraft.world.WorldServer world) {
        try {
            java.lang.reflect.Field field = net.minecraft.world.WorldServer.class.getDeclaredField("field_73063_M"); // thePlayerManager
            retrofit.legacy.compat.FinalFields.set(field, world, new BridgePlayerManager(world));
        } catch (Throwable t) {
            System.out.println("[Retrofit] Could not bridge the world's player manager: " + t);
        }
    }

    /** checkChunksExist over the host's whole height (1.20.1 worlds go below y 0). */
    static boolean areaLoaded(HostWorld host, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        if (host == null) {
            return false;
        }
        for (int x = minX >> 4; x <= maxX >> 4; x++) {
            for (int z = minZ >> 4; z <= maxZ >> 4; z++) {
                if (!host.blockLoaded(x << 4, Math.max(minY, Math.min(maxY, 64)), z << 4)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** The host level behind a bridged world, or null for other worlds. */
    static HostWorld hostOf(World world) {
        if (world instanceof BridgeWorldServer) {
            return ((BridgeWorldServer) world).host();
        }
        if (world instanceof BridgeWorld) {
            return ((BridgeWorld) world).host();
        }
        return null;
    }

    /** The legacy tile entity at a position; for host inventories (vanilla chests, furnaces...) a stand-in (HostTiles). */
    static TileEntity getTileEntity(World world, HostWorld host, int x, int y, int z) {
        if (host == null) {
            return null;
        }
        TileEntity tile = (TileEntity) host.getLegacyTileEntity(x, y, z);
        if (tile == null && ModernContent.isStandIn(getBlock(host, x, y, z))) {
            return modernTile(world, host, x, y, z); // a block 1.7.10 never had: what it is, for mods that move it
        }
        if (tile != null || !host.hasHostBlockEntity(x, y, z)) {
            return tile; // (most places have no 1.20.1 block entity: no stand-in to look for)
        }
        tile = HostTiles.at(world, host, x, y, z);
        return tile != null ? tile : HostSpawner.at(world, host, x, y, z);
    }

    private static TileEntity modernTile(World world, HostWorld host, int x, int y, int z) {
        byte[] data = host.hostBlockData(x, y, z);
        if (data == null) {
            return null;
        }
        try {
            TileEntity tile = new ModernContent.ModernTile(Bridge.fromBytes(data));
            tile.func_145834_a(world); // setWorldObj
            tile.field_145851_c = x;
            tile.field_145848_d = y;
            tile.field_145849_e = z;
            return tile;
        } catch (java.io.IOException e) {
            return null;
        }
    }

    static void setTileEntity(HostWorld host, World world, int x, int y, int z, TileEntity tile) {
        if (host == null || tile == null) {
            return;
        }
        if (tile instanceof ModernContent.ModernTile) {
            // a moved 1.20.1 block arrives: the real block goes where its stand-in's tile entity is placed
            NBTTagCompound block = ((ModernContent.ModernTile) tile).block();
            if (block != null) {
                try {
                    host.setHostBlockData(x, y, z, Bridge.toBytes(block));
                } catch (java.io.IOException e) {
                    // nothing placed
                }
            }
            return;
        }
        tile.func_145834_a(world); // setWorldObj
        tile.field_145851_c = x;
        tile.field_145848_d = y;
        tile.field_145849_e = z;
        tile.func_145829_t(); // validate
        host.setLegacyTileEntity(x, y, z, tile);
    }

    static boolean spawnEntity(HostWorld host, Entity entity) {
        if (host == null) {
            return false;
        }
        if (entity instanceof EntityItem) {
            EntityItem item = (EntityItem) entity;
            return host.spawnItem(item.field_70165_t, item.field_70163_u, item.field_70161_v, item.func_92059_d(), // pos, getEntityItem
                    item.field_70159_w, item.field_70181_x, item.field_70179_y, item.field_145804_b); // motion, delayBeforeCanPickup
        }
        if (entity instanceof EntityXPOrb) {
            EntityXPOrb orb = (EntityXPOrb) entity;
            return host.spawnExperience(orb.field_70165_t, orb.field_70163_u, orb.field_70161_v, orb.func_70526_d()); // getXpValue
        }
        if (Entities.entityName(entity) != null) {
            return host.spawnLegacyEntity(entity);
        }
        try {
            byte[] data = Entities.vanillaData(entity);
            return data != null && host.spawnVanillaEntity(data, Entities.ownerUuid(entity));
        } catch (java.io.IOException e) {
            return false;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static List collisionBoxes(HostWorld host, World world, Entity entity, AxisAlignedBB box) {
        List boxes = new ArrayList();
        if (host == null) {
            return boxes;
        }
        double[] found = host.getCollisionBoxes(box.field_72340_a, box.field_72338_b, box.field_72339_c,
                box.field_72336_d, box.field_72337_e, box.field_72334_f);
        for (int o = 0; o + 6 <= found.length; o += 6) {
            boxes.add(AxisAlignedBB.func_72330_a(found[o], found[o + 1], found[o + 2], found[o + 3], found[o + 4], found[o + 5]));
        }
        for (Object other : selectEntities(world, Entity.class, box.func_72314_b(0.25, 0.25, 0.25), null)) { // expand
            if (other != entity) {
                AxisAlignedBB otherBox = ((Entity) other).func_70046_E(); // getBoundingBox (boats, minecarts)
                if (otherBox != null && otherBox.func_72326_a(box)) { // intersectsWith
                    boxes.add(otherBox);
                }
            }
        }
        return boxes;
    }

    /** Entities in a box: the legacy chunks are empty, so scan the world's entity list. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static List selectEntities(World world, Class type, AxisAlignedBB box, IEntitySelector selector) {
        List result = new ArrayList();
        for (Object element : new ArrayList(world.field_72996_f)) { // loadedEntityList
            Entity entity = (Entity) element;
            if (type.isInstance(entity) && entity.field_70121_D.func_72326_a(box) // boundingBox.intersectsWith
                    && (selector == null || selector.func_82704_a(entity))) { // isEntityApplicable
                result.add(entity);
            }
        }
        // The host's own creatures, items and orbs, as stand-ins (only for queries that could match one).
        if (type.isAssignableFrom(HostCreatureProxies.Zombie.class) || type.isAssignableFrom(net.minecraft.entity.item.EntityItem.class)
                || type.isAssignableFrom(net.minecraft.entity.item.EntityXPOrb.class) || !net.minecraft.entity.EntityLivingBase.class.isAssignableFrom(type)
                || net.minecraft.entity.EntityLiving.class.isAssignableFrom(type)) {
            for (Entity entity : HostCreatures.inBox(world, box)) {
                if (type.isInstance(entity) && entity.field_70121_D.func_72326_a(box) && (selector == null || selector.func_82704_a(entity))) {
                    result.add(entity);
                }
            }
        }
        return result;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static List entitiesExcluding(World world, Entity exclude, AxisAlignedBB box, IEntitySelector selector) {
        List result = selectEntities(world, Entity.class, box, selector);
        result.remove(exclude);
        return result;
    }

    static BiomeGenBase biome(HostWorld host, int x, int z) {
        BiomeGenBase biome = biomeFor(host == null ? "minecraft:plains" : host.getBiome(x, z));
        return biome != null ? biome : BiomeGenBase.field_76772_c; // plains
    }

    /** The 1.7.10 biome closest to a 1.20.1 biome id (by name, with the renames between them), or null. */
    static BiomeGenBase biomeFor(String id) {
        if (biomesByName == null) {
            Map<String, BiomeGenBase> byName = new HashMap<String, BiomeGenBase>();
            for (BiomeGenBase biome : BiomeGenBase.func_150565_n()) { // getBiomeGenArray
                if (biome != null) {
                    byName.put(normalize(biome.field_76791_y), biome); // biomeName
                }
            }
            biomesByName = byName;
        }
        String path = id.substring(id.indexOf(':') + 1);
        String alias = BIOME_ALIASES.get(path);
        return biomesByName.get(normalize(alias != null ? alias : path)); // null: 1.7.10 had nothing like it
    }

    private static String normalize(String name) {
        return name.toLowerCase(Locale.ROOT).replace("_", "").replace(" ", "");
    }
}
