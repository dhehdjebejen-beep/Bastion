package dev.bastionac.mixin;

import dev.bastionac.BastionAC;
import dev.bastionac.core.ShadowTelemetry;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Records the authoritative result of a block placement after vanilla has made
 * its decision. This hook is telemetry-only: it does not cancel, modify or
 * retry placement, and it never creates a BastionAC violation.
 */
@Mixin(BlockItem.class)
public abstract class BlockItemPlacementOutcomeMixin {

    @Inject(method = "place", at = @At("RETURN"))
    private void bastionac$recordPlacementOutcome(ItemPlacementContext context,
                                                  CallbackInfoReturnable<ActionResult> cir) {
        if (!BastionAC.config().general.shadowTelemetryEnabled) return;
        if (!(context.getPlayer() instanceof ServerPlayerEntity)) return;
        ShadowTelemetry.recordPlacementOutcome(cir.getReturnValue().isAccepted());
    }
}
