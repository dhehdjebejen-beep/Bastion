package dev.bastionac.mixin;

import dev.bastionac.BastionAC;
import dev.bastionac.core.MobTeleports;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Notes every successful random teleport of a mob: an enderman, a chorus fruit (3.7.0 b21). */
@Mixin(LivingEntity.class)
public abstract class MobTeleportMixin {
    @Inject(method = "teleport(DDDZ)Z", at = @At("RETURN"))
    private void bastionac$noteTeleport(double x, double y, double z, boolean particles,
                                         CallbackInfoReturnable<Boolean> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (cir.getReturnValueZ() && !self.getEntityWorld().isClient() && !(self instanceof PlayerEntity)) {
            MobTeleports.note(self.getId(), BastionAC.serverTick());
        }
    }
}
