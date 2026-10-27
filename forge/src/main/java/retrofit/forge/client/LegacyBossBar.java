package retrofit.forge.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.minecraft.world.BossEvent;

import java.util.UUID;

/**
 * 1.7.10's vanilla-style boss bar, shown through the host's own boss bar overlay: the last boss
 * that rendered set BossStatus (name, health fraction, timer), and GuiIngameForge.renderBossHealth
 * gates it (mods with their own bar turn it off - The Titans). The legacy side runs the timer down
 * and reports the state once per HUD frame ({@code ClientEvents.bossBar}); this side keeps one
 * entry in the overlay for it, drawn and lerped like the host's own bosses' bars. 1.7.10's bar was
 * the pink progress bar, with none of the music, screen darkening or fog the modern packet carries.
 */
public final class LegacyBossBar {
    /** A fixed id of our own, so the entry survives progress and name updates and nothing claims it. */
    private static final UUID ID = new UUID(0x726574726F666974L, 0x526574726F466974L); // "retrofit" twice, as hex
    private static BossEvent bar;
    private static Object level;
    private static boolean failed;

    private LegacyBossBar() {
    }

    /** Tests: the name on the legacy boss bar the overlay shows, or null if none. */
    public static String shown() {
        BossEvent shown = bar;
        return shown == null ? null : shown.getName().getString();
    }

    /** The legacy state ({show, name or null, progress}), read once per HUD frame. */
    static void update(Object[] state) {
        if (failed || state == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.gui == null) {
            return;
        }
        BossHealthOverlay overlay = minecraft.gui.getBossOverlay();
        if (overlay == null) {
            return;
        }
        try {
            if (minecraft.level != level) { // the overlay is emptied when the level changes; our entry went with it
                level = minecraft.level;
                bar = null;
            }
            boolean show = state[0] instanceof Boolean on && on;
            if (!show) {
                if (bar != null) {
                    overlay.update(ClientboundBossEventPacket.createRemovePacket(ID));
                    bar = null;
                }
                return;
            }
            Component name = Component.literal(state[1] instanceof String text && !text.isEmpty() ? text : "Boss");
            float progress = state[2] instanceof Float fraction ? Math.max(0.0f, Math.min(1.0f, fraction)) : 0.0f;
            if (bar == null) {
                bar = new BossEvent(ID, name, BossEvent.BossBarColor.PINK, BossEvent.BossBarOverlay.PROGRESS) {
                };
                bar.setProgress(progress);
                overlay.update(ClientboundBossEventPacket.createAddPacket(bar));
            } else {
                if (!bar.getName().equals(name)) {
                    bar.setName(name);
                    overlay.update(ClientboundBossEventPacket.createUpdateNamePacket(bar));
                }
                if (bar.getProgress() != progress) {
                    bar.setProgress(progress);
                    overlay.update(ClientboundBossEventPacket.createUpdateProgressPacket(bar));
                }
            }
        } catch (RuntimeException e) {
            failed = true; // cosmetic: carry on without it, reported once
            retrofit.forge.world.LegacyBridge.reportClientFailure("the legacy boss bar", e);
        }
    }
}
