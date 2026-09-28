package dev.bastionclaims.mixin;

import dev.bastionclaims.core.ProtectionService;
import net.minecraft.block.BlockState;
import net.minecraft.block.SaplingBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Closes the tree-griefing hole: a sapling planted just outside a claim grows
 * into it, dropping logs and leaves inside somebody else's base. Nothing in the
 * block-protection path sees that, because the blocks are placed by world
 * generation rather than by a player.
 *
 * <p>A sapling standing <b>inside</b> a claim grows normally — that is the
 * owner's own tree and it may lean over the border. A sapling on open ground
 * within reach of someone else's claim simply refuses to grow. Refusing is used
 * rather than trimming the tree because trimming would mean intercepting world
 * generation, which also runs off the server thread during chunk population.
 */
@Mixin(SaplingBlock.class)
public abstract class SaplingGrowthMixin {

    @Inject(method = "generate", at = @At("HEAD"), cancellable = true)
    private void bastionclaims$dontGrowIntoClaims(ServerWorld world, BlockPos pos, BlockState state,
                                                  Random random, CallbackInfo ci) {
        if (ProtectionService.treeWouldReachForeignClaim(world, pos)) ci.cancel();
    }
}
