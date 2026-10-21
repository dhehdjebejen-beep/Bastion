package dev.bastionclaims.mixin;

import dev.bastionclaims.core.ProtectionService;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.ai.brain.task.MoveItemsTask;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

/**
 * A copper golem carries items from copper chests to chests up to 32 blocks
 * away (1.20). Nobody owns it, and it walks wherever it likes: one built next
 * to a claim drained the owner's copper chests and filled their chests. It now
 * sees a container in a claim that keeps hoppers out only when it belongs to
 * that claim — built there, or standing there when this version first saw it
 * ({@link ProtectionService#golemBlocked}).
 */
@Mixin(MoveItemsTask.class)
public abstract class CopperGolemMixin {

    @Inject(method = "getStorageFor", at = @At("RETURN"), cancellable = true)
    private void bastionclaims$guardClaim(PathAwareEntity entity, World world, BlockEntity blockEntity,
                                          Set<GlobalPos> visited, Set<GlobalPos> unreachable, Box box,
                                          CallbackInfoReturnable<MoveItemsTask.Storage> cir) {
        MoveItemsTask.Storage storage = cir.getReturnValue();
        if (storage != null && ProtectionService.golemBlocked(entity, storage.pos())) cir.setReturnValue(null);
    }
}
