package retrofit.testmods.reference;

import net.minecraft.client.Minecraft;
import net.minecraft.util.MouseHelper;

/**
 * The game's mouse, never grabbed: a reference run plays without input, and grabbing would take
 * the pointer from whoever is using the computer. The view only turns when the driver turns it.
 */
final class NoGrabMouse extends MouseHelper {
    static void install() {
        Minecraft mc = Minecraft.func_71410_x();
        mc.field_71417_B = new NoGrabMouse(); // mouseHelper
        mc.field_71474_y.field_82881_y = false; // pauseOnLostFocus: the window is never focused
    }

    @Override
    public void func_74372_a() { // grabMouseCursor
    }

    @Override
    public void func_74373_b() { // ungrabMouseCursor
    }

    @Override
    public void func_74374_c() { // mouseXYChange
        field_74377_a = 0; // deltaX
        field_74375_b = 0; // deltaY
    }
}
