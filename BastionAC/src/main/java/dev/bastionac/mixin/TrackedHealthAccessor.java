package dev.bastionac.mixin;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.data.TrackedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The tracked health field, so the info shield can recognise it in an outgoing metadata packet. */
@Mixin(LivingEntity.class)
public interface TrackedHealthAccessor {

    @Accessor("HEALTH")
    static TrackedData<Float> bastionac$health() {
        throw new AssertionError();
    }
}
