package dev.bastionclaims.mixin;

import dev.bastionclaims.core.ProtectionService;
import net.minecraft.block.BlockState;
import net.minecraft.block.FireBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Closes the fire-griefing hole: scaffolding (or a tree) set alight just
 * outside a claim border used to burn straight through it, because fire
 * spread is world-driven — no player action is involved, so the
 * build/interact protection never sees it.
 *
 * <p>A fire block standing inside a claim whose owner has not enabled fire
 * spread simply refuses to tick: it neither ages nor propagates, and the
 * build behind the border stays intact. On open ground and inside claims with
 * {@code allowFireSpread} on, vanilla behaviour is untouched. Note that this
 * also stops the owner's own fire inside a protected claim — the flag is the
 * single switch, exactly like {@code allowExplosions}.
 */
@Mixin(FireBlock.class)
public abstract class FireBlockMixin {

    @Inject(method = "scheduledTick", at = @At("HEAD"), cancellable = true)
    private void bastionclaims$stopFireInsideClaims(BlockState state, ServerWorld world, BlockPos pos,
                                                     Random random, CallbackInfo ci) {
        String dim = world.getRegistryKey().getValue().toString();
        if (ProtectionService.fireSpreadBlocked(dim, pos.getX(), pos.getY(), pos.getZ())) ci.cancel();
    }

    /**
     * A fire burning just OUTSIDE the claim still ticks, and its tick reaches
     * into the neighbours: {@code trySpreadingFire} burns the adjacent block
     * away or sets it alight. Stopping only the fires inside left exactly that
     * open — the wall on the border burned from the outside in.
     */
    @Inject(method = "trySpreadingFire", at = @At("HEAD"), cancellable = true)
    private void bastionclaims$noBurnAcross(World world, BlockPos pos, int spreadFactor, Random random,
                                            int currentAge, CallbackInfo ci) {
        String dim = world.getRegistryKey().getValue().toString();
        if (ProtectionService.fireSpreadBlocked(dim, pos.getX(), pos.getY(), pos.getZ())) ci.cancel();
    }

    /** The same outside fire also seeds new fire a few blocks around it — not into the claim. */
    @Redirect(method = "scheduledTick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/world/ServerWorld;setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;I)Z"))
    private boolean bastionclaims$noSeedAcross(ServerWorld world, BlockPos pos, BlockState state, int flags) {
        // Only new fire placed elsewhere is refused; the fire ageing in place
        // (same position) is the claim-free fire itself.
        if (state.getBlock() instanceof FireBlock) {
            String dim = world.getRegistryKey().getValue().toString();
            if (ProtectionService.fireSpreadBlocked(dim, pos.getX(), pos.getY(), pos.getZ())) return false;
        }
        return world.setBlockState(pos, state, flags);
    }
}
