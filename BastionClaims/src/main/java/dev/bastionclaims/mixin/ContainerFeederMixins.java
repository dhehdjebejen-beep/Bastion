package dev.bastionclaims.mixin;

import dev.bastionclaims.core.ProtectionService;
import net.minecraft.block.BlockState;
import net.minecraft.block.DispenserBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Blocks that push an item into the container in front of them (1.20). A
 * dropper and a crafter put it there through
 * {@code HopperBlockEntity.getInventoryAt}, not through the hopper's own
 * input and output lookups that {@link HopperBlockEntityMixin} guards: one
 * standing on the border could not take anything out of a chest in the claim,
 * but could fill it with rubbish. Now they follow the hopper rule — a
 * container in a claim that keeps hoppers out accepts nothing from a feeder
 * outside it (the owner's own neighbouring claim excepted). The item stays in
 * the dropper; the crafter does not craft.
 */
public final class ContainerFeederMixins {

    private ContainerFeederMixins() {}

    @Mixin(net.minecraft.block.DropperBlock.class)
    public abstract static class Dropper {
        @Inject(method = "dispense", at = @At("HEAD"), cancellable = true)
        private void bastionclaims$guardBorder(ServerWorld world, BlockState state, BlockPos pos, CallbackInfo ci) {
            if (!state.contains(DispenserBlock.FACING)) return;
            if (ProtectionService.feederBlocked(world, pos, pos.offset(state.get(DispenserBlock.FACING)))) ci.cancel();
        }
    }

    @Mixin(net.minecraft.block.CrafterBlock.class)
    public abstract static class Crafter {
        @Inject(method = "craft", at = @At("HEAD"), cancellable = true)
        private void bastionclaims$guardBorder(BlockState state, ServerWorld world, BlockPos pos, CallbackInfo ci) {
            if (!state.contains(Properties.ORIENTATION)) return;
            BlockPos front = pos.offset(state.get(Properties.ORIENTATION).getFacing());
            if (ProtectionService.feederBlocked(world, pos, front)) ci.cancel();
        }
    }
}
