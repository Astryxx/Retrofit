package retrofit.forge.world;

import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;

import java.util.Map;
import java.util.function.Consumer;

/** A legacy creature's health range, without changing the shared modern MAX_HEALTH attribute. */
final class LegacyHealthAttribute extends AttributeInstance {
    private boolean valueDirty = true;
    private double value;

    private LegacyHealthAttribute(Consumer<AttributeInstance> onDirty) {
        super(Attributes.MAX_HEALTH, onDirty);
    }

    @SuppressWarnings("unchecked")
    static void install(AttributeMap attributes) {
        AttributeInstance previous = attributes.getInstance(Attributes.MAX_HEALTH);
        if (previous == null || previous instanceof LegacyHealthAttribute) return;
        // Keep the registered attribute as the key and in save/network data. Only this entity's
        // instance changes; global bounds, vanilla creatures and other mods' instances do not.
        Map<Attribute, AttributeInstance> instances = ObfuscationReflectionHelper.getPrivateValue(
                AttributeMap.class, attributes, "f_22139_"); // attributes
        var replacement = new LegacyHealthAttribute(instance -> attributes.getDirtyAttributes().add(instance));
        replacement.replaceFrom(previous);
        instances.put(Attributes.MAX_HEALTH, replacement);
        attributes.getDirtyAttributes().remove(previous);
    }

    @Override
    protected void setDirty() {
        valueDirty = true;
        super.setDirty();
    }

    @Override
    public double getValue() {
        if (valueDirty) {
            // The modern calculation's operation order, retaining modifiers and their serialization.
            double added = getBaseValue();
            for (var modifier : getModifiers(AttributeModifier.Operation.ADDITION)) added += modifier.getAmount();
            double multiplied = added;
            for (var modifier : getModifiers(AttributeModifier.Operation.MULTIPLY_BASE)) multiplied += added * modifier.getAmount();
            for (var modifier : getModifiers(AttributeModifier.Operation.MULTIPLY_TOTAL)) multiplied *= 1 + modifier.getAmount();
            // LivingEntity stores health as a float. Keep the normal positive lower bound and
            // finite representable range, while allowing the native mod's boss/minion values.
            value = Double.isNaN(multiplied) ? 1 : Math.max(1, Math.min(Float.MAX_VALUE, multiplied));
            valueDirty = false;
        }
        return value;
    }
}
