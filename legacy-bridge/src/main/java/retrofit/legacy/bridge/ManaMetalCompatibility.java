package retrofit.legacy.bridge;

import net.minecraft.entity.player.EntityPlayer;

/** Optional ManaMetal rules at the boundary where the host would otherwise perform a vanilla action. */
final class ManaMetalCompatibility {
    private static java.lang.reflect.Method weaponRule;
    private static volatile boolean checked;

    private ManaMetalCompatibility() { }

    private static synchronized void initialize() {
        if (!checked) {
            if (cpw.mods.fml.common.Loader.isModLoaded("manametalmod")) {
                try {
                    weaponRule = Class.forName("project.studio.manametalmod.MMM", false, ManaMetalCompatibility.class.getClassLoader())
                            .getMethod("playerCanUseWeapon", EntityPlayer.class);
                } catch (ReflectiveOperationException e) { throw new IllegalStateException("ManaMetal weapon rule unavailable", e); }
            }
            checked = true;
        }
    }

    static boolean canUseWeapon(EntityPlayer player) {
        if (!checked) initialize();
        if (weaponRule == null) return true;
        try {
            // A failed rule makes ManaMetal's combat handler return without applying its own
            // damage. The host must also stop its ordinary attack/use, rather than letting that
            // become an alternative way to damage a target with an unusable weapon.
            return !"fail".equals(((Enum<?>) weaponRule.invoke(null, player)).name());
        } catch (ReflectiveOperationException e) { throw new IllegalStateException("ManaMetal weapon rule failed", e); }
    }
}
