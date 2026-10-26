package retrofit.testmods.reference;

import net.minecraft.launchwrapper.Launch;
import org.lwjgl.opengl.Display;

/**
 * Starts real 1.7.10 as the launcher does (LaunchWrapper with FML's tweaker), with its window
 * placed off the screen before it is created, so a reference run doesn't get in the way of whoever
 * is using the computer. The game draws into its framebuffer, which screenshots read.
 */
public final class ReferenceMain {
    private ReferenceMain() {
    }

    public static void main(String[] args) {
        Display.setLocation(-4000, -4000);
        Launch.main(args);
    }
}
