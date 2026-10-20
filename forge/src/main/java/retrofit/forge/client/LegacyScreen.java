package retrofit.forge.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import retrofit.forge.world.LegacyMenu;

/**
 * A legacy container GUI (GuiContainer) over a {@link LegacyMenu}. The legacy screen draws itself
 * and handles its own input; slot clicks it makes come back through {@link #legacyClick} and are
 * sent like any 1.20.1 click. 1.20.1 draws the slot items, the cursor stack and tooltips.
 */
public class LegacyScreen extends AbstractContainerScreen<LegacyMenu> {
    private final LegacyGuiHost gui;

    public LegacyScreen(LegacyMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.gui = new LegacyGuiHost(menu.legacyScreen(), this);
    }

    @Override
    protected void init() {
        super.init();
        gui.init(width, height);
        int[] layout = gui.layout();
        if (layout[0] > 0) {
            imageWidth = layout[0];
            imageHeight = layout[1];
            leftPos = layout[2];
            topPos = layout[3];
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        gui.render(graphics, mouseX, mouseY, partialTicks, menu);
        ItemStack carried = menu.getCarried();
        if (!carried.isEmpty()) {
            graphics.pose().pushPose();
            graphics.pose().translate(0, 0, 232);
            graphics.renderItem(carried, mouseX - 8, mouseY - 8);
            graphics.renderItemDecorations(font, carried, mouseX - 8, mouseY - 8);
            graphics.pose().popPose();
        }
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTicks, int mouseX, int mouseY) {
        // The legacy screen draws its own background.
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
    }

    /** A slot click the legacy screen made (GuiContainer.handleMouseClick). */
    public void legacyClick(int slotId, int button, int mode) {
        Slot slot = slotId >= 0 && slotId < menu.slots.size() ? menu.slots.get(slotId) : null;
        slotClicked(slot, slotId, button, ClickType.values()[Math.max(0, Math.min(mode, ClickType.values().length - 1))]);
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        return gui.mouseClicked(x, y, button);
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        return gui.mouseReleased(x, y, button);
    }

    @Override
    public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        return gui.mouseDragged(x, y, button);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double delta) {
        return gui.mouseScrolled(delta);
    }

    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        return gui.keyPressed(key, modifiers);
    }

    @Override
    public boolean charTyped(char character, int modifiers) {
        return gui.charTyped(character);
    }

    @Override
    protected void containerTick() {
        gui.tick();
    }

    @Override
    public void removed() {
        super.removed();
        if (!handedOver) {
            gui.closed();
        }
    }

    private boolean handedOver;

    /** The legacy GUI moves to another screen (see ClientHooks.takeClientOnlyScreen): don't close it with this one. */
    void handOver() {
        handedOver = true;
    }

    @Override
    public boolean isPauseScreen() {
        return gui.pauses();
    }
}
