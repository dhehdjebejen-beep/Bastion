package dev.bastionclaims.mixin;

import dev.bastionclaims.core.ProtectionService;
import net.minecraft.block.piston.PistonHandler;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Prevents a piston from moving or breaking blocks inside a claim it does not
 * belong to. This blocks the classic mechanism attacks: a (sticky) piston placed
 * just outside a border pulling blocks out of a claim, or pushing blocks across
 * the border into someone else's claim.
 *
 * <p>{@code calculatePush} builds the list of blocks a push would move/break; we
 * veto the whole push if any affected cell — a moved block's current position,
 * its destination, or a broken block — is in a protected claim whose interior
 * does not contain the piston base ({@code posFrom}).
 */
@Mixin(PistonHandler.class)
public abstract class PistonHandlerMixin {

    @Shadow @Final private World world;
    @Shadow @Final private BlockPos posFrom;
    @Shadow @Final private Direction motionDirection;
    @Shadow @Final private List<BlockPos> movedBlocks;
    @Shadow @Final private List<BlockPos> brokenBlocks;

    @Inject(method = "calculatePush", at = @At("RETURN"), cancellable = true)
    private void bastionclaims$guardClaims(CallbackInfoReturnable<Boolean> cir) {
        if (world.isClient() || !Boolean.TRUE.equals(cir.getReturnValue())) return;
        String dim = world.getRegistryKey().getValue().toString();
        int px = posFrom.getX(), py = posFrom.getY(), pz = posFrom.getZ();

        // The piston head itself occupies the cell in front of the base when
        // it extends — a head poking into a neighbour's claim pushes entities
        // and blocks doorways even when no block moves.
        BlockPos head = posFrom.offset(motionDirection);
        if (ProtectionService.pistonCellBlocked(dim, head.getX(), head.getY(), head.getZ(), px, py, pz)) {
            cir.setReturnValue(false);
            return;
        }

        for (BlockPos moved : movedBlocks) {
            if (ProtectionService.pistonCellBlocked(dim, moved.getX(), moved.getY(), moved.getZ(), px, py, pz)) {
                cir.setReturnValue(false);
                return;
            }
            BlockPos dest = moved.offset(motionDirection);
            if (ProtectionService.pistonCellBlocked(dim, dest.getX(), dest.getY(), dest.getZ(), px, py, pz)) {
                cir.setReturnValue(false);
                return;
            }
        }
        for (BlockPos broken : brokenBlocks) {
            if (ProtectionService.pistonCellBlocked(dim, broken.getX(), broken.getY(), broken.getZ(), px, py, pz)) {
                cir.setReturnValue(false);
                return;
            }
        }
    }
}
