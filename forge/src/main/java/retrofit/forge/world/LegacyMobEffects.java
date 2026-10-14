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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mods' potions (1.7.10 Potion ids above 23) as 1.20.1 effects, so they show on the player like
 * any effect (HUD, inventory) with the mod's name and colour. They do nothing themselves: the
 * legacy potion acts on the player's mirror (see LegacyPlayers.tickServer).
 */
@Mod.EventBusSubscriber(modid = RetrofitMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class LegacyMobEffects {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<Integer, MobEffect> BY_ID = new HashMap<>();
    /** id -> {name key, colour} for the asset pack (icons and names). */
    private static final Map<Integer, Object[]> INFO = new LinkedHashMap<>();

    private LegacyMobEffects() {
    }

    public static MobEffect byLegacyId(int id) {
        return BY_ID.get(id);
    }

    public static Map<Integer, Object[]> info() {
        return INFO;
    }

    public static ResourceLocation idOf(int legacyId) {
        return new ResourceLocation(RetrofitMod.MOD_ID, "legacy_potion_" + legacyId);
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
            for (Object[] potion : potions) {
                int id = (Integer) potion[0];
                String name = (String) potion[1];
                int colour = (Integer) potion[2];
                boolean harmful = (Integer) potion[3] != 0;
                MobEffect effect = new Display(harmful ? MobEffectCategory.HARMFUL : MobEffectCategory.BENEFICIAL, colour, name);
                helper.register(idOf(id), effect);
                BY_ID.put(id, effect);
                INFO.put(id, new Object[]{name, colour});
            }
            if (!BY_ID.isEmpty()) {
                LOGGER.info("Registered {} legacy potion effects", BY_ID.size());
            }
        });
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
