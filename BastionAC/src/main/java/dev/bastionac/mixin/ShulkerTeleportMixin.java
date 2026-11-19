package dev.bastionac.mixin;

import dev.bastionac.BastionAC;
import dev.bastionac.core.MobTeleports;
import net.minecraft.entity.mob.ShulkerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A shulker moves to another wall without the living-entity teleport (3.7.0 b21). */
@Mixin(ShulkerEntity.class)
public abstract class ShulkerTeleportMixin {
    @Inject(method = "tryTeleport", at = @At("RETURN"))
    private void bastionac$noteTeleport(CallbackInfoReturnable<Boolean> cir) {
        ShulkerEntity self = (ShulkerEntity) (Object) this;
        if (cir.getReturnValueZ() && !self.getEntityWorld().isClient()) {
            MobTeleports.note(self.getId(), BastionAC.serverTick());
        }
    }
}
