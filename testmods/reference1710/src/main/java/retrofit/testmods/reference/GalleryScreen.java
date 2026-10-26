package retrofit.testmods.reference;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.item.ItemStack;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.util.List;

/**
 * The items on a grid, laid out as Retrofit's ItemGallery: 16 by 9 grey cells of 26 GUI pixels in
 * the middle of a mid-grey screen, each item drawn at 1.5 times its size.
 */
final class GalleryScreen extends GuiScreen {
    private static final int COLUMNS = 16;
    private static final int ROWS = 9;
    private static final int CELL = 26;

    private final List<ItemStack> stacks;
    int page;

    GalleryScreen(List<ItemStack> stacks) {
        this.stacks = stacks;
    }

    static int pages(int count) {
        return (count + COLUMNS * ROWS - 1) / (COLUMNS * ROWS);
    }

    @Override
    public void func_73863_a(int mouseX, int mouseY, float partialTicks) { // drawScreen
        func_73734_a(0, 0, field_146294_l, field_146295_m, 0xFF707070); // drawRect over width, height
        int left = (field_146294_l - COLUMNS * CELL) / 2;
        int top = (field_146295_m - ROWS * CELL) / 2;
        int perPage = COLUMNS * ROWS;
        for (int i = 0; i < perPage; i++) {
            int n = page * perPage + i;
            if (n >= stacks.size()) {
                break;
            }
            int x = left + (i % COLUMNS) * CELL;
            int y = top + (i / COLUMNS) * CELL;
            func_73734_a(x, y, x + CELL - 2, y + CELL - 2, 0xFF8B8B8B);
            ItemStack stack = stacks.get(n);
            if (stack == null) {
                continue;
            }
            GL11.glPushMatrix();
            GL11.glTranslatef(x + 1, y + 1, 0);
            GL11.glScalef(1.5f, 1.5f, 1);
            GL11.glEnable(GL12.GL_RESCALE_NORMAL);
            RenderHelper.func_74520_c(); // enableGUIStandardItemLighting
            try {
                field_146296_j.func_82406_b(field_146289_q, field_146297_k.func_110434_K(), stack, 0, 0); // itemRender.renderItemAndEffectIntoGUI(fontRendererObj, mc.getTextureManager(), ...)
                field_146296_j.func_77021_b(field_146289_q, field_146297_k.func_110434_K(), stack, 0, 0); // renderItemOverlayIntoGUI
            } catch (RuntimeException e) {
                func_73734_a(0, 0, 16, 16, 0xFFFF0000); // drawing failed: red, as in Retrofit's
            }
            RenderHelper.func_74518_a(); // disableStandardItemLighting
            GL11.glDisable(GL12.GL_RESCALE_NORMAL);
            GL11.glPopMatrix();
        }
        field_146289_q.func_78261_a("gallery page " + (page + 1) + "/" + pages(stacks.size()), 4, 4, 0xFFFFFF); // drawStringWithShadow
    }

    @Override
    public boolean func_73868_f() { // doesGuiPauseGame: the world keeps going, as on 1.20.1
        return false;
    }
}
