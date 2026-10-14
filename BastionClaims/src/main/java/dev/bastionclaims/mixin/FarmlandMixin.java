package dev.bastionclaims.mixin;

import dev.bastionclaims.core.ProtectionService;
import net.minecraft.block.BlockState;
import net.minecraft.block.FarmlandBlock;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Jumping on someone's farmland turns it to dirt and uproots the crop — the
 * cheapest grief there is, open to anybody who can walk into the claim. A
 * stranger (or a mob, under the mob-grief flag) no longer tramples farmland
 * inside a claim; members still can. Moisture drying out goes another way
 * and is untouched.
 */
@Mixin(FarmlandBlock.class)
public abstract class FarmlandMixin {

    @Inject(method = "onLandedUpon", at = @At("HEAD"), cancellable = true)
    private void bastionclaims$guardTrample(World world, BlockState state, BlockPos pos, Entity entity,
                                            double fallDistance, CallbackInfo ci) {
        if (!world.isClient() && ProtectionService.actorBlockedAt(world, pos, entity)) {
            // Still take the fall damage — only the trampling is refused.
            entity.handleFallDamage(fallDistance, 1.0f, entity.getDamageSources().fall());
            ci.cancel();
        }
    }
}
