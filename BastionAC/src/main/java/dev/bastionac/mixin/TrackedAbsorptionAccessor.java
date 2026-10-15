package dev.bastionac.mixin;

import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The tracked absorption field (golden hearts), so the info shield can recognise it. */
@Mixin(PlayerEntity.class)
public interface TrackedAbsorptionAccessor {

    @Accessor("ABSORPTION_AMOUNT")
    static TrackedData<Float> bastionac$absorption() {
        throw new AssertionError();
    }
}
