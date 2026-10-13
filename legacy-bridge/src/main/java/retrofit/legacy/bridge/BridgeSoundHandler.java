package retrofit.legacy.bridge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.ISound;
import net.minecraft.client.audio.SoundCategory;
import net.minecraft.client.audio.SoundHandler;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.entity.player.EntityPlayer;

/**
 * Client only. Minecraft.getSoundHandler() for legacy code (GUI buttons, mods' own sounds): each
 * sound is played by the host, by name, at its position (or at the player for GUI sounds).
 * Created without a constructor, so the legacy sound system never starts.
 */
final class BridgeSoundHandler extends SoundHandler {
    private BridgeSoundHandler() {
        super(null, null);
    }

    /** playSound */
    @Override
    public void func_147682_a(ISound sound) {
        Minecraft minecraft = Minecraft.func_71410_x();
        if (sound == null || !(minecraft.field_71441_e instanceof BridgeWorldClient)) { // theWorld
            return;
        }
        BridgeWorldClient world = (BridgeWorldClient) minecraft.field_71441_e;
        double x = sound.func_147649_g(), y = sound.func_147654_h(), z = sound.func_147651_i();
        EntityPlayer player = minecraft.field_71439_g;
        if (sound.func_147656_j() == ISound.AttenuationType.NONE && player != null) { // GUI sounds: no position
            x = player.field_70165_t;
            y = player.field_70163_u;
            z = player.field_70161_v;
        }
        if (world.host() != null) {
            world.host().playSound(x, y, z, sound.func_147650_b().toString(), sound.func_147653_e(), sound.func_147655_f());
        }
    }

    @Override
    public void func_147681_a(ISound sound, int delay) {
        func_147682_a(sound);
    }

    @Override
    public void func_110549_a(IResourceManager manager) {
    }

    @Override
    public void func_147691_a(EntityPlayer player, float partialTicks) {
    }

    @Override
    public void func_147689_b() {
    }

    @Override
    public void func_147690_c() {
    }

    @Override
    public void func_147685_d() {
    }

    @Override
    public void func_73660_a() {
    }

    @Override
    public void func_147687_e() {
    }

    @Override
    public void func_147684_a(SoundCategory category, float volume) {
    }

    @Override
    public void func_147683_b(ISound sound) {
    }

    @Override
    public boolean func_147692_c(ISound sound) {
        return false;
    }
}
