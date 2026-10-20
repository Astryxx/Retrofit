package retrofit.legacy.bridge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiIngame;
import net.minecraft.client.gui.GuiNewChat;
import net.minecraft.util.IChatComponent;

/**
 * Client only. Minecraft.ingameGUI for mods that use it outside drawing: chat messages they print
 * themselves (IC2's mode switches, Thaumcraft's notices) appear in the host's chat. The host draws
 * the HUD; this one never draws.
 */
final class BridgeGuiIngame extends GuiIngame {
    private final Chat chat;

    BridgeGuiIngame(Minecraft minecraft) {
        super(minecraft);
        chat = new Chat(minecraft);
    }

    /** getChatGUI */
    @Override
    public GuiNewChat func_146158_b() {
        return chat;
    }

    /** The chat: lines go to the host's chat (and stay here too, for mods that read them back). */
    static final class Chat extends GuiNewChat {
        Chat(Minecraft minecraft) {
            super(minecraft);
        }

        /** printChatMessage */
        @Override
        public void func_146227_a(IChatComponent component) {
            func_146234_a(component, 0);
        }

        /** printChatMessageWithOptionalDeletion */
        @Override
        public void func_146234_a(IChatComponent component, int id) {
            Minecraft minecraft = Minecraft.func_71410_x();
            if (component != null && minecraft.field_71439_g instanceof BridgeClientPlayer) { // thePlayer
                ((BridgeClientPlayer) minecraft.field_71439_g).host.sendMessage(IChatComponent.Serializer.func_150696_a(component));
            }
            try {
                super.func_146234_a(component, id);
            } catch (RuntimeException e) {
                // the legacy chat's own line wrapping needs a font; the host has the message already
            }
        }
    }
}
