package retrofit.testmods.hello;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.util.ResourceLocation;

/** The crate's client GUI: the vanilla dispenser background, a live tick count and a Ping button. */
public class CrateGui extends GuiContainer {
    private static final ResourceLocation BACKGROUND = new ResourceLocation("textures/gui/container/dispenser.png");
    private final CrateContainer container;
    private final int x, y, z;
    static int pongs;

    public CrateGui(InventoryPlayer playerInventory, CrateTile crate) {
        super(new CrateContainer(playerInventory, crate));
        this.container = (CrateContainer) field_147002_h;
        this.x = crate.field_145851_c;
        this.y = crate.field_145848_d;
        this.z = crate.field_145849_e;
    }

    /** initGui */
    @Override
    @SuppressWarnings("unchecked")
    public void func_73866_w_() {
        super.func_73866_w_();
        int left = (field_146294_l - field_146999_f) / 2; // (width - xSize) / 2
        int top = (field_146295_m - field_147000_g) / 2;
        field_146292_n.add(new GuiButton(0, left + 118, top + 20, 50, 20, "Ping")); // buttonList
    }

    /** actionPerformed */
    @Override
    protected void func_146284_a(GuiButton button) {
        if (button.field_146127_k == 0) { // id
            Hello1710.network.sendToServer(new PingMessage(x, y, z));
            System.out.println("[hello1710] Ping sent from the crate GUI");
        }
    }

    /** drawGuiContainerForegroundLayer */
    @Override
    protected void func_146979_b(int mouseX, int mouseY) {
        field_146289_q.func_78276_b("Crate: " + container.clientTicks + " ticks", 8, 6, 0x404040); // fontRendererObj.drawString
        field_146289_q.func_78276_b("Pongs: " + pongs, 118, 46, 0x404040);
        field_146289_q.func_78276_b("Inventory", 8, field_147000_g - 94, 0x404040);
    }

    /** drawGuiContainerBackgroundLayer */
    @Override
    protected void func_146976_a(float partialTicks, int mouseX, int mouseY) {
        field_146297_k.func_110434_K().func_110577_a(BACKGROUND); // mc.getTextureManager().bindTexture
        int left = (field_146294_l - field_146999_f) / 2;
        int top = (field_146295_m - field_147000_g) / 2;
        func_73729_b(left, top, 0, 0, field_146999_f, field_147000_g); // drawTexturedModalRect
    }
}
