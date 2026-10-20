package retrofit.forge.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import retrofit.forge.world.LegacyStacks;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws what a legacy GUI drew (the GL emulation's capture, in GUI coordinates) with 1.20.1's
 * GUI renderer, and the items, slots and tooltips the legacy code left markers for, in drawing
 * order: quads up to a marker, then the marker's item, and so on. Tooltips go on top at the end.
 */
final class LegacyGuiRenderer {
    private static final int FLOATS = LegacyCapture.FLOATS_PER_VERTEX;

    private LegacyGuiRenderer() {
    }

    private static final boolean DEBUG = Boolean.getBoolean("retrofit.debugGui");
    private static final java.util.Set<String> REPORTED = new java.util.HashSet<>();

    /** Hold the legacy render lock from the legacy draw until this returns. */
    static void draw(GuiGraphics graphics, int vertices, Object[] markers, AbstractContainerMenu menu) throws Throwable {
        // Copies: drawing an item can run a legacy item renderer, which reuses the capture.
        float[] d = java.util.Arrays.copyOf(LegacyCapture.data(), vertices * FLOATS);
        int[] states = java.util.Arrays.copyOf(LegacyCapture.states(), vertices);
        if (DEBUG && REPORTED.add(menu == null ? "hud" : menu.getClass().getName() + menu.containerId)) {
            java.util.Map<String, int[]> perTexture = new java.util.TreeMap<>();
            for (int v = 0; v + 3 < vertices; v += 4) {
                int state = states[v];
                String key = (state & LegacyCapture.UNTEXTURED) != 0 ? "untextured" : LegacyCapture.texture(state & LegacyCapture.TEXTURE_MASK).toString();
                int o = v * FLOATS;
                int[] entry = perTexture.computeIfAbsent(key, k -> new int[2]);
                entry[0]++;
                if (entry[1]++ == 0) {
                    com.mojang.logging.LogUtils.getLogger().info("[Retrofit] GUI capture {}: first quad at {},{},{} uv {},{} colour {},{},{},{}", key,
                            d[o], d[o + 1], d[o + 2], d[o + 3], d[o + 4], d[o + 8], d[o + 9], d[o + 10], d[o + 11]);
                }
            }
            perTexture.forEach((key, count) -> com.mojang.logging.LogUtils.getLogger().info("[Retrofit] GUI capture: {} quads of {}", count[0], key));
            StringBuilder dump = new StringBuilder();
            int nextMarker = 0;
            for (int v = 0; v + 3 < vertices; v += 4) {
                while (nextMarker < markers.length && (Integer) ((Object[]) markers[nextMarker])[0] <= v) {
                    dump.append("  marker ").append(((Object[]) markers[nextMarker])[1]).append('\n');
                    nextMarker++;
                }
                int o = v * FLOATS;
                int o2 = (v + 2) * FLOATS;
                dump.append(String.format("  quad %d tex %s (%.1f,%.1f,%.1f)-(%.1f,%.1f) rgba %.2f %.2f %.2f %.2f%n", v / 4,
                        (states[v] & LegacyCapture.UNTEXTURED) != 0 ? "-" : String.valueOf(states[v] & LegacyCapture.TEXTURE_MASK),
                        d[o], d[o + 1], d[o + 2], d[o2], d[o2 + 1], d[o + 8], d[o + 9], d[o + 10], d[o + 11]));
            }
            com.mojang.logging.LogUtils.getLogger().info("[Retrofit] GUI capture dump:\n{}", dump);
            com.mojang.logging.LogUtils.getLogger().info("[Retrofit] GUI capture: {} markers", markers.length);
        }
        Font font = Minecraft.getInstance().font;
        MultiBufferSource.BufferSource buffers = graphics.bufferSource();
        List<Object[]> tooltips = new ArrayList<>();
        int marker = 0;
        int v = 0;
        int currentState = Integer.MIN_VALUE;
        VertexConsumer out = null;
        while (true) {
            int nextMarker = marker < markers.length ? (Integer) ((Object[]) markers[marker])[0] : Integer.MAX_VALUE;
            if (v + 3 < vertices && v < nextMarker) {
                Matrix4f pose = graphics.pose().last().pose();
                int state = states[v];
                if (state != currentState || out == null) {
                    currentState = state;
                    out = buffers.getBuffer(renderType(state));
                }
                boolean textured = (state & LegacyCapture.UNTEXTURED) == 0;
                for (int corner = 0; corner < 4; corner++) {
                    int o = (v + corner) * FLOATS;
                    out.vertex(pose, d[o], d[o + 1], d[o + 2]).color(d[o + 8], d[o + 9], d[o + 10], d[o + 11]);
                    if (textured) {
                        out.uv(d[o + 3], d[o + 4]).uv2(LightTexture.FULL_BRIGHT);
                    }
                    out.endVertex();
                }
                v += 4;
                continue;
            }
            if (marker >= markers.length) {
                break;
            }
            graphics.flush();
            out = null;
            Object[] entry = (Object[]) markers[marker++];
            drawMarker(graphics, font, (String) entry[1], entry[2], (float[]) entry[3], menu, tooltips);
        }
        graphics.flush();
        for (Object[] tooltip : tooltips) {
            graphics.renderTooltip(font, (ItemStack) tooltip[0], (Integer) tooltip[1], (Integer) tooltip[2]);
        }
    }

    /**
     * 1.7.10 drew GUI layers with depth testing off, so later drawing simply covers earlier
     * drawing (labels over items, overlays over slots): the see-through render types do the same.
     */
    private static RenderType renderType(int state) throws Throwable {
        if ((state & LegacyCapture.UNTEXTURED) != 0) {
            return RenderType.guiOverlay();
        }
        return RenderType.textSeeThrough(LegacyCapture.texture(state & LegacyCapture.TEXTURE_MASK));
    }

    private static void drawMarker(GuiGraphics graphics, Font font, String kind, Object subject, float[] at,
                                   AbstractContainerMenu menu, List<Object[]> tooltips) {
        int x = Math.round(at[0]);
        int y = Math.round(at[1]);
        switch (kind) {
            case "slot" -> {
                int index = (Integer) subject;
                if (menu != null && index >= 0 && index < menu.slots.size()) {
                    ItemStack stack = menu.slots.get(index).getItem();
                    graphics.renderItem(stack, x, y);
                    graphics.renderItemDecorations(font, stack, x, y);
                }
            }
            case "item" -> {
                ItemStack stack = LegacyStacks.toModern(subject);
                float scale = at[3];
                if (Math.abs(scale - 1) < 0.01f) {
                    graphics.renderItem(stack, x, y);
                } else {
                    graphics.pose().pushPose();
                    graphics.pose().translate(at[0], at[1], 0);
                    graphics.pose().scale(scale, scale, 1);
                    graphics.renderItem(stack, 0, 0);
                    graphics.pose().popPose();
                }
            }
            case "overlay" -> {
                Object[] overlay = (Object[]) subject;
                ItemStack stack = LegacyStacks.toModern(overlay[0]);
                graphics.renderItemDecorations(font, stack, x, y, (String) overlay[1]);
            }
            case "tooltip" -> {
                ItemStack stack = LegacyStacks.toModern(subject);
                if (!stack.isEmpty()) {
                    tooltips.add(new Object[]{stack, x, y});
                }
            }
            default -> {
            }
        }
    }
}
