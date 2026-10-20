package retrofit.forge.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * A legacy GUI screen without a container (books, sign and note editors, configuration screens),
 * opened by legacy client code with Minecraft.displayGuiScreen. It draws itself and handles its
 * own input; what it changes reaches the server through the mod's own packets.
 */
public class LegacyPlainScreen extends Screen {
    private final LegacyGuiHost gui;

    public LegacyPlainScreen(Object legacyScreen) {
        super(Component.empty());
        this.gui = new LegacyGuiHost(legacyScreen, this);
    }

    Object legacyScreen() {
        return gui.screen();
    }

    @Override
    protected void init() {
        gui.init(width, height);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        gui.render(graphics, mouseX, mouseY, partialTicks, null);
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
    public void tick() {
        gui.tick();
    }

    @Override
    public void removed() {
        gui.closed();
    }

    @Override
    public boolean isPauseScreen() {
        return gui.pauses();
    }
}
