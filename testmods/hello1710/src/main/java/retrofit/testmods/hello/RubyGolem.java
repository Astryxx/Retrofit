package retrofit.testmods.hello;

import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.EntityAIAttackOnCollide;
import net.minecraft.entity.ai.EntityAIHurtByTarget;
import net.minecraft.entity.ai.EntityAILookIdle;
import net.minecraft.entity.ai.EntityAINearestAttackableTarget;
import net.minecraft.entity.ai.EntityAISwimming;
import net.minecraft.entity.ai.EntityAIWander;
import net.minecraft.entity.ai.EntityAIWatchClosest;
import net.minecraft.entity.monster.EntityMob;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.world.World;

/** A 1.7.10 mob with its own AI tasks (wander, watch, chase and hit players) that drops rubies. */
public class RubyGolem extends EntityMob {
    public RubyGolem(World world) {
        super(world);
        func_70105_a(0.7f, 1.9f); // setSize
        field_70714_bg.func_75776_a(0, new EntityAISwimming(this)); // tasks.addTask
        field_70714_bg.func_75776_a(2, new EntityAIAttackOnCollide(this, EntityPlayer.class, 1.0, false));
        field_70714_bg.func_75776_a(5, new EntityAIWander(this, 1.0));
        field_70714_bg.func_75776_a(6, new EntityAIWatchClosest(this, EntityPlayer.class, 8.0f));
        field_70714_bg.func_75776_a(7, new EntityAILookIdle(this));
        field_70715_bh.func_75776_a(1, new EntityAIHurtByTarget(this, true)); // targetTasks
        field_70715_bh.func_75776_a(2, new EntityAINearestAttackableTarget(this, EntityPlayer.class, 0, true));
    }

    /** isAIEnabled: use the task-based AI above */
    @Override
    protected boolean func_70650_aV() {
        return true;
    }

    /** applyEntityAttributes */
    @Override
    protected void func_110147_ax() {
        super.func_110147_ax();
        func_110148_a(SharedMonsterAttributes.field_111267_a).func_111128_a(30); // maxHealth
        func_110148_a(SharedMonsterAttributes.field_111263_d).func_111128_a(0.25); // movementSpeed
        func_110148_a(SharedMonsterAttributes.field_111264_e).func_111128_a(3); // attackDamage
    }

    /** getDropItem */
    @Override
    protected Item func_146068_u() {
        return Hello1710.gem;
    }
}
