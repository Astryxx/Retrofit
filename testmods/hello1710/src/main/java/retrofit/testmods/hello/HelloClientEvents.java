package retrofit.testmods.hello;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.client.event.RenderGameOverlayEvent;

/** Client only: a key binding with a KeyInputEvent handler, and text on the HUD, the 1.7.10 way. */
public class HelloClientEvents {
    private final KeyBinding ping = new KeyBinding("key.hello1710.ping", 37, "key.categories.hello1710"); // K
    private int presses;

    public HelloClientEvents() {
        ClientRegistry.registerKeyBinding(ping);
    }

    @SubscribeEvent
    public void onKey(InputEvent.KeyInputEvent event) {
        while (ping.func_151468_f()) { // isPressed
            presses++;
            System.out.println("[hello1710] Ping key pressed (" + presses + ")");
        }
    }

    @SubscribeEvent
    public void onHudPre(RenderGameOverlayEvent.Pre event) {
        if (event.type == RenderGameOverlayEvent.ElementType.ALL) {
            Minecraft.func_71410_x().field_71466_p.func_78261_a("1.7.10 HUD pre fires", 4, 14, 0x55FF55); // drawStringWithShadow
        }
    }

    @SubscribeEvent
    public void onHud(RenderGameOverlayEvent.Post event) {
        if (event.type == RenderGameOverlayEvent.ElementType.ALL) {
            Minecraft.func_71410_x().field_71466_p.func_78261_a("1.7.10 HUD: key pressed " + presses + " times", 4, 4, 0xFFAA00); // drawStringWithShadow
        }
    }
}
