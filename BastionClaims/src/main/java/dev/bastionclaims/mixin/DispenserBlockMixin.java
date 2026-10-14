package dev.bastionclaims.mixin;

import dev.bastionclaims.core.ProtectionService;
import net.minecraft.block.BlockState;
import net.minecraft.block.DispenserBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A dispenser outside a claim may not act on the cell in front of it when
 * that cell is inside the claim. Dispenser behaviours run without a player,
 * so none of the player-side guards see them: a dispenser on the border could
 * pour lava, light fires, spawn primed TNT, place boats or shulkers, shear
 * sheep or bone-meal crops one block into somebody else's land.
 *
 * <p>Droppers override {@code dispense} and are not affected — they only spit
 * items out or push them into a container, which the hopper rules cover.
 */
@Mixin(DispenserBlock.class)
public abstract class DispenserBlockMixin {

    @Inject(method = "dispense", at = @At("HEAD"), cancellable = true)
    private void bastionclaims$guardBorder(ServerWorld world, BlockState state, BlockPos pos, CallbackInfo ci) {
        if (!state.contains(DispenserBlock.FACING)) return;
        BlockPos target = pos.offset(state.get(DispenserBlock.FACING));
        if (ProtectionService.dispenserBlocked(world.getRegistryKey().getValue().toString(),
                target.getX(), target.getY(), target.getZ(), pos.getX(), pos.getY(), pos.getZ())) {
            // Cancelled before the item is taken: it stays in the dispenser.
            ci.cancel();
        }
    }
}
