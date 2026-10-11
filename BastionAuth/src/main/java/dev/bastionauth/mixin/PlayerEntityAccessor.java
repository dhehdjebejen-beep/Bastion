package dev.bastionauth.mixin;

import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The absorption hearts are tracked data; {@link dev.bastionauth.core.StateMask} hides them. */
@Mixin(PlayerEntity.class)
public interface PlayerEntityAccessor {

    @Accessor("ABSORPTION_AMOUNT")
    static TrackedData<Float> bastionauth$absorption() {
        throw new AssertionError();
    }
}
