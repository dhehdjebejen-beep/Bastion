package dev.bastionclaims.mixin;

import dev.bastionclaims.core.ProtectionService;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lightning called down with a Channeling trident does nothing inside a claim
 * the thrower may not build in: no fire, and no creature struck (no damage,
 * no villager turned witch, no pig turned piglin). Natural lightning is
 * weather and passes untouched; players are left to the PvP rules.
 */
@Mixin(LightningEntity.class)
public abstract class LightningMixin {

    @Shadow
    private ServerPlayerEntity channeler;

    @Inject(method = "spawnFire", at = @At("HEAD"), cancellable = true)
    private void bastionclaims$guardFire(int spreadAttempts, CallbackInfo ci) {
        LightningEntity self = (LightningEntity) (Object) this;
        if (ProtectionService.channeledLightningBlockedAt(self.getEntityWorld(), self.getBlockPos(), channeler)) {
            ci.cancel();
        }
    }

    /**
     * Natural lightning used to leave fire on spawn and in claims. It burnt
     * nothing (the claim stops fire from ticking) but the flames stayed and
     * crept. Each fire block the bolt would set — under it and the few around
     * it — is skipped where fire is not allowed.
     */
    @Redirect(method = "spawnFire", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/world/ServerWorld;setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;)Z"))
    private boolean bastionclaims$noFireInClaims(ServerWorld world, net.minecraft.util.math.BlockPos pos,
                                                 net.minecraft.block.BlockState state) {
        String dim = world.getRegistryKey().getValue().toString();
        if (ProtectionService.fireSpreadBlocked(dim, pos.getX(), pos.getY(), pos.getZ())) return false;
        return world.setBlockState(pos, state);
    }

    @Redirect(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/entity/Entity;onStruckByLightning(Lnet/minecraft/server/world/ServerWorld;Lnet/minecraft/entity/LightningEntity;)V"))
    private void bastionclaims$guardStrike(Entity victim, ServerWorld world, LightningEntity lightning) {
        if (!(victim instanceof PlayerEntity)
                && !(victim instanceof net.minecraft.entity.mob.HostileEntity)
                && ProtectionService.channeledLightningBlockedAt(world, victim.getBlockPos(), channeler)) {
            return;
        }
        victim.onStruckByLightning(world, lightning);
    }
}
