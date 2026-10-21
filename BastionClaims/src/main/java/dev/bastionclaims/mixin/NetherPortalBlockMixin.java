package dev.bastionclaims.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.bastionclaims.core.PortalPlan;
import dev.bastionclaims.core.ProtectionService;
import net.minecraft.block.NetherPortalBlock;
import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.BlockLocating;
import net.minecraft.world.dimension.PortalForcer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;
import java.util.Optional;

/**
 * A portal lit in one dimension builds its twin in the other when there is
 * none near the spot (1.20). Vanilla chooses the place by the terrain alone and
 * then writes obsidian over whatever stands there and clears the air in front —
 * inside somebody's claim as readily as anywhere, chests included.
 *
 * <p>The portal is now planned first: every block it would write is recorded
 * ({@link PortalForcerMixin}), not written. If none of them lies in a claim
 * the traveller may not build in, the plan is written exactly as vanilla
 * would have; otherwise nothing is written and the traveller stays where they
 * are, told why. Nothing was ever changed, so nothing has to be put back —
 * putting back a chest would not put back what was in it.
 */
@Mixin(NetherPortalBlock.class)
public abstract class NetherPortalBlockMixin {

    @WrapOperation(method = "getOrCreateExitPortalTarget", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/dimension/PortalForcer;createPortal(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/util/math/Direction$Axis;)Ljava/util/Optional;"))
    private Optional<BlockLocating.Rectangle> bastionclaims$planFirst(PortalForcer forcer, BlockPos pos, Direction.Axis axis,
                                                                     Operation<Optional<BlockLocating.Rectangle>> original,
                                                                     @Local(argsOnly = true) ServerWorld world,
                                                                     @Local(argsOnly = true) Entity entity) {
        PortalPlan.begin();
        Optional<BlockLocating.Rectangle> made;
        List<PortalPlan.Cell> cells;
        try {
            made = original.call(forcer, pos, axis);
        } finally {
            cells = PortalPlan.end();
        }
        if (made.isEmpty()) return made;
        if (ProtectionService.portalBlocked(world, entity, cells)) {
            PortalPlan.markRefused();
            return Optional.empty();
        }
        PortalPlan.write(world, cells);
        return made;
    }

    /** Vanilla's "likely target out of worldborder" is not why: said once, by us, to the traveller. */
    @WrapOperation(method = "getOrCreateExitPortalTarget", at = @At(value = "INVOKE",
            target = "Lorg/slf4j/Logger;error(Ljava/lang/String;)V"))
    private void bastionclaims$quietRefusal(org.slf4j.Logger logger, String message, Operation<Void> original) {
        if (PortalPlan.takeRefused()) return;
        original.call(logger, message);
    }
}
