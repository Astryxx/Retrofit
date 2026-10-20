package retrofit.legacy.bridge;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

/**
 * The legacy client's view entity (Minecraft.renderViewEntity, RenderManager.livingPlayer),
 * placed at the host camera every frame. Renderers use it to face text and sprites towards the
 * viewer and to skip work by distance. It holds nothing and is never added to a world.
 */
final class CameraEntity extends EntityLivingBase {
    CameraEntity(World world) {
        super(world);
    }

    /** getHeldItem */
    @Override
    public ItemStack func_70694_bm() {
        return null;
    }

    /** getEquipmentInSlot */
    @Override
    public ItemStack func_71124_b(int slot) {
        return null;
    }

    /** setCurrentItemOrArmor */
    @Override
    public void func_70062_b(int slot, ItemStack stack) {
    }

    /** getLastActiveItems */
    @Override
    public ItemStack[] func_70035_c() {
        return new ItemStack[5];
    }
}
