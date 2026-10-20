package retrofit.forge.world;

import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 1.7.10 sound and particle names in 1.20.1.
 *
 * - Vanilla sounds were renamed in 1.9 ("random.click" became "ui.button.click"); the common ones
 *   are mapped here. Unmapped vanilla names are tried as they are, then dropped.
 * - Mod sounds ("modid:name") keep their names: the asset pack serves each mod's sounds.json
 *   (with names made lower case, as 1.20.1 requires) and its sound files.
 * - Particles are client-side only, as in 1.7.10; parameters that were colours stay colours.
 */
final class LegacyEffects {
    private static final Map<String, String> SOUNDS = new HashMap<>();
    private static final Map<String, SimpleParticleType> PARTICLES = new HashMap<>();
    private static final Map<String, SoundEvent> SOUND_CACHE = new ConcurrentHashMap<>();
    private static final SoundEvent NONE = SoundEvent.createVariableRangeEvent(new ResourceLocation("retrofit", "none"));

    static {
        String[][] sounds = {
                {"random.click", "ui.button.click"}, {"random.pop", "entity.item.pickup"},
                {"random.orb", "entity.experience_orb.pickup"}, {"random.levelup", "entity.player.levelup"},
                {"random.explode", "entity.generic.explode"}, {"random.fizz", "block.fire.extinguish"},
                {"random.door_open", "block.wooden_door.open"}, {"random.door_close", "block.wooden_door.close"},
                {"random.chestopen", "block.chest.open"}, {"random.chestclosed", "block.chest.close"},
                {"random.bow", "entity.arrow.shoot"}, {"random.bowhit", "entity.arrow.hit"},
                {"random.break", "entity.item.break"}, {"random.anvil_use", "block.anvil.use"},
                {"random.anvil_land", "block.anvil.land"}, {"random.anvil_break", "block.anvil.destroy"},
                {"random.glass", "block.glass.break"}, {"random.burp", "entity.player.burp"},
                {"random.eat", "entity.generic.eat"}, {"random.drink", "entity.generic.drink"},
                {"random.splash", "entity.generic.splash"}, {"random.wood_click", "block.wooden_button.click_on"},
                {"random.successful_hit", "entity.arrow.hit_player"}, {"random.hurt", "entity.player.hurt"},
                {"random.fuse", "entity.tnt.primed"}, {"random.classic_hurt", "entity.player.hurt"},
                {"fire.fire", "block.fire.ambient"}, {"fire.ignite", "item.flintandsteel.use"},
                {"liquid.water", "block.water.ambient"}, {"liquid.lava", "block.lava.ambient"},
                {"liquid.lavapop", "block.lava.pop"}, {"liquid.splash", "entity.generic.splash"},
                {"liquid.swim", "entity.generic.swim"}, {"game.player.swim", "entity.player.swim"},
                {"portal.portal", "block.portal.ambient"}, {"portal.trigger", "block.portal.trigger"},
                {"portal.travel", "block.portal.travel"}, {"tile.piston.out", "block.piston.extend"},
                {"tile.piston.in", "block.piston.contract"}, {"note.harp", "block.note_block.harp"},
                {"note.bd", "block.note_block.basedrum"}, {"note.snare", "block.note_block.snare"},
                {"note.hat", "block.note_block.hat"}, {"note.bassattack", "block.note_block.bass"},
                {"note.bass", "block.note_block.bass"}, {"note.pling", "block.note_block.pling"},
                {"ambient.weather.rain", "weather.rain"}, {"ambient.weather.thunder", "entity.lightning_bolt.thunder"},
                {"ambient.cave.cave", "ambient.cave"}, {"damage.hit", "entity.player.hurt"},
                {"damage.fallsmall", "entity.generic.small_fall"}, {"damage.fallbig", "entity.generic.big_fall"},
                {"mob.chicken.plop", "entity.chicken.egg"}, {"mob.endermen.portal", "entity.enderman.teleport"},
                {"mob.ghast.fireball", "entity.ghast.shoot"}, {"mob.zombie.metal", "entity.zombie.attack_iron_door"},
                {"mob.zombie.wood", "entity.zombie.attack_wooden_door"}, {"mob.zombie.woodbreak", "entity.zombie.break_wooden_door"},
                {"mob.wither.spawn", "entity.wither.spawn"}, {"mob.enderdragon.growl", "entity.ender_dragon.growl"},
                {"minecart.base", "entity.minecart.riding"}, {"minecart.inside", "entity.minecart.inside"},
        };
        for (String[] sound : sounds) {
            SOUNDS.put(sound[0], sound[1]);
        }
        // dig.* and step.* name the block material; 1.20.1 names the sound type.
        String[][] materials = {{"stone", "stone"}, {"wood", "wood"}, {"gravel", "gravel"}, {"grass", "grass"},
                {"sand", "sand"}, {"snow", "snow"}, {"cloth", "wool"}, {"ladder", "ladder"}};
        for (String[] material : materials) {
            SOUNDS.put("dig." + material[0], "block." + material[1] + ".break");
            SOUNDS.put("step." + material[0], "block." + material[1] + ".step");
        }
        SOUNDS.put("dig.glass", "block.glass.break");

        Object[][] particles = {
                {"smoke", ParticleTypes.SMOKE}, {"largesmoke", ParticleTypes.LARGE_SMOKE}, {"flame", ParticleTypes.FLAME},
                {"portal", ParticleTypes.PORTAL}, {"heart", ParticleTypes.HEART}, {"explode", ParticleTypes.POOF},
                {"largeexplode", ParticleTypes.EXPLOSION}, {"hugeexplosion", ParticleTypes.EXPLOSION_EMITTER},
                {"bubble", ParticleTypes.BUBBLE}, {"splash", ParticleTypes.SPLASH}, {"wake", ParticleTypes.FISHING},
                {"crit", ParticleTypes.CRIT}, {"magicCrit", ParticleTypes.ENCHANTED_HIT},
                {"enchantmenttable", ParticleTypes.ENCHANT}, {"note", ParticleTypes.NOTE}, {"lava", ParticleTypes.LAVA},
                {"spell", ParticleTypes.EFFECT}, {"instantSpell", ParticleTypes.INSTANT_EFFECT},
                {"mobSpell", ParticleTypes.ENTITY_EFFECT}, {"mobSpellAmbient", ParticleTypes.AMBIENT_ENTITY_EFFECT},
                {"witchMagic", ParticleTypes.WITCH}, {"cloud", ParticleTypes.CLOUD}, {"dripWater", ParticleTypes.DRIPPING_WATER},
                {"dripLava", ParticleTypes.DRIPPING_LAVA}, {"townaura", ParticleTypes.MYCELIUM},
                {"depthsuspend", ParticleTypes.MYCELIUM}, {"suspended", ParticleTypes.UNDERWATER},
                {"happyVillager", ParticleTypes.HAPPY_VILLAGER}, {"angryVillager", ParticleTypes.ANGRY_VILLAGER},
                {"fireworksSpark", ParticleTypes.FIREWORK}, {"snowshovel", ParticleTypes.SNOWFLAKE},
        };
        for (Object[] particle : particles) {
            PARTICLES.put((String) particle[0], (SimpleParticleType) particle[1]);
        }
    }

    private LegacyEffects() {
    }

    /** The 1.20.1 sound for a legacy sound name, or null if there is none. */
    static SoundEvent sound(String legacyName) {
        SoundEvent event = SOUND_CACHE.computeIfAbsent(legacyName, LegacyEffects::resolveSound);
        return event == NONE ? null : event;
    }

    private static SoundEvent resolveSound(String legacyName) {
        int colon = legacyName.indexOf(':');
        if (colon > 0 && !legacyName.startsWith("minecraft:")) {
            // Mod sounds are defined by the mod's own sounds.json, which the asset pack serves.
            ResourceLocation location = ResourceLocation.tryParse(sanitize(legacyName));
            return location == null ? NONE : SoundEvent.createVariableRangeEvent(location);
        }
        String path = colon > 0 ? legacyName.substring(colon + 1) : legacyName;
        String mapped = SOUNDS.getOrDefault(path, path);
        ResourceLocation location = ResourceLocation.tryParse("minecraft:" + sanitize(mapped));
        SoundEvent event = location == null ? null : ForgeRegistries.SOUND_EVENTS.getValue(location);
        return event == null ? NONE : event;
    }

    /** 1.20.1 resource names are lower case without spaces; legacy ones weren't always. */
    static String sanitize(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.:/-]", "_");
    }

    /** The 1.20.1 particle for a legacy particle name and its parameters, or null. */
    static ParticleOptions particle(String name, double vx, double vy, double vz) {
        SimpleParticleType simple = PARTICLES.get(name);
        if (simple != null) {
            return simple;
        }
        if (name.equals("reddust")) {
            float red = vx == 0 ? 1 : (float) vx; // 1.7.10 used the velocity as the colour
            return new DustParticleOptions(new Vector3f(red, (float) vy, (float) vz), 1);
        }
        if (name.equals("snowballpoof")) {
            return new ItemParticleOption(ParticleTypes.ITEM, new ItemStack(net.minecraft.world.item.Items.SNOWBALL));
        }
        if (name.equals("slime")) {
            return new ItemParticleOption(ParticleTypes.ITEM, new ItemStack(net.minecraft.world.item.Items.SLIME_BALL));
        }
        String[] parts = name.split("_");
        try {
            if (parts[0].equals("iconcrack") && parts.length >= 2) {
                Object item = LegacyBridge.itemById(Integer.parseInt(parts[1]));
                int meta = parts.length >= 3 ? Integer.parseInt(parts[2]) : 0;
                ItemStack stack = item == null ? ItemStack.EMPTY : LegacyStacks.toModern(LegacyBridge.makeStackOf(item, 1, meta, null));
                return stack.isEmpty() ? null : new ItemParticleOption(ParticleTypes.ITEM, stack);
            }
            if ((parts[0].equals("blockcrack") || parts[0].equals("blockdust")) && parts.length >= 2) {
                Object block = LegacyBridge.blockById(Integer.parseInt(parts[1]));
                int meta = parts.length >= 3 ? Integer.parseInt(parts[2]) : 0;
                BlockState state = block == null ? null : LegacyBlockTable.modernState(block, meta);
                return state == null ? null : new BlockParticleOption(
                        parts[0].equals("blockcrack") ? ParticleTypes.BLOCK : ParticleTypes.FALLING_DUST, state);
            }
        } catch (NumberFormatException ignored) {
            // A malformed name; draw nothing.
        }
        return null;
    }
}
