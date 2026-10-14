package dev.bastionclaims.mixin;

import dev.bastionclaims.core.ProtectionService;
import net.minecraft.block.BlockState;
import net.minecraft.enchantment.EnchantmentEffectContext;
import net.minecraft.enchantment.effect.entity.ReplaceDiskEnchantmentEffect;
import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Frost Walker (a "replace disk" enchantment effect) freezes water into a
 * path; inside someone's claim it only does so for those who may build there
 * — a moat is part of a base.
 */
@Mixin(ReplaceDiskEnchantmentEffect.class)
public abstract class FrostWalkerMixin {

    @Unique
    private static final ThreadLocal<Entity> bastionclaims$user = new ThreadLocal<>();

    @Inject(method = "apply", at = @At("HEAD"))
    private void bastionclaims$remember(ServerWorld world, int level, EnchantmentEffectContext context, Entity user,
                                        Vec3d pos, CallbackInfo ci) {
        bastionclaims$user.set(user);
    }

    @Inject(method = "apply", at = @At("RETURN"))
    private void bastionclaims$forget(ServerWorld world, int level, EnchantmentEffectContext context, Entity user,
                                      Vec3d pos, CallbackInfo ci) {
        bastionclaims$user.remove();
    }

    @Redirect(method = "apply", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/world/ServerWorld;setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;)Z"))
    private boolean bastionclaims$guard(ServerWorld world, BlockPos pos, BlockState state) {
        if (ProtectionService.actorBlockedAt(world, pos, bastionclaims$user.get())) return false;
        return world.setBlockState(pos, state);
    }
}
