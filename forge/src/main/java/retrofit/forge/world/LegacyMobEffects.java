package retrofit.forge.world;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.RegisterEvent;
import org.slf4j.Logger;
import retrofit.forge.RetrofitMod;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Mods' potions (1.7.10 Potion ids above 23) as 1.20.1 effects, so they show on the player like
 * any effect (HUD, inventory) with the mod's name and colour. They do nothing themselves: the
 * legacy potion acts on the player's mirror (see LegacyPlayers.tickServer). Each is registered under
 * its name key ("potion.paralysis" is retrofit:paralysis), not its number: numbers depend on which
 * mods are installed (a clashing id is moved, see LegacyIds), and saved effects keep their names.
 */
@Mod.EventBusSubscriber(modid = RetrofitMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class LegacyMobEffects {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<Integer, MobEffect> BY_ID = new HashMap<>();
    /** id -> {name key, colour, 1.20.1 id} for the asset pack (icons and names). */
    private static final Map<Integer, Object[]> INFO = new LinkedHashMap<>();

    private LegacyMobEffects() {
    }

    public static MobEffect byLegacyId(int id) {
        return BY_ID.get(id);
    }

    public static Map<Integer, Object[]> info() {
        return INFO;
    }

    @SubscribeEvent
    public static void onRegister(RegisterEvent event) {
        if (RetrofitMod.runtime() == null) {
            return;
        }
        event.register(Registries.MOB_EFFECT, helper -> {
            Object[][] potions;
            try {
                potions = LegacyBridge.call("modPotions");
            } catch (RuntimeException e) {
                LOGGER.error("Could not list the legacy mods' potions", e);
                return;
            }
            Set<ResourceLocation> taken = new HashSet<>();
            // named in an order that doesn't depend on the ids configs give them (see LegacyEnchantments)
            java.util.Arrays.sort(potions, java.util.Comparator.comparing((Object[] potion) -> (String) potion[1]).thenComparing(potion -> (String) potion[4]));
            for (Object[] potion : potions) {
                int id = (Integer) potion[0];
                String name = (String) potion[1];
                int colour = (Integer) potion[2];
                boolean harmful = (Integer) potion[3] != 0;
                MobEffect effect = new Display(harmful ? MobEffectCategory.HARMFUL : MobEffectCategory.BENEFICIAL, colour, name);
                ResourceLocation key = idOf(name, (String) potion[4], taken);
                helper.register(key, effect);
                if (id >= 0) { // (a potion that lost its slot has no legacy id: registered so client and server match)
                    BY_ID.put(id, effect);
                    INFO.put(id, new Object[]{name, colour, key});
                }
            }
            if (potions.length > 0) {
                LOGGER.info("Registered {} legacy potion effects ({} without a slot of their own)", potions.length, potions.length - BY_ID.size());
            }
        });
    }

    /** The potion's name key as a 1.20.1 id; a second potion with the same key gets a number at the end. */
    private static ResourceLocation idOf(String nameKey, String className, Set<ResourceLocation> taken) {
        String path = nameKey.toLowerCase(Locale.ROOT).replaceFirst("^potion\\.", "").replaceAll("[^a-z0-9_./-]", "_");
        ResourceLocation base = new ResourceLocation(RetrofitMod.MOD_ID, path.isEmpty()
                ? "potion_" + className.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_") : path);
        ResourceLocation id = base;
        for (int n = 2; !taken.add(id); n++) {
            id = new ResourceLocation(base.getNamespace(), base.getPath() + "_" + n);
        }
        return id;
    }

    /** A mod potion as shown on the player; its name is the mod's own translation key. */
    static final class Display extends MobEffect {
        private final String nameKey;

        Display(MobEffectCategory category, int colour, String nameKey) {
            super(category, colour);
            this.nameKey = nameKey;
        }

        @Override
        public String getDescriptionId() {
            return nameKey;
        }

        @Override
        public boolean isDurationEffectTick(int duration, int amplifier) {
            return false; // the legacy potion acts on the mirror
        }
    }
}
