package dev.bastionclaims.mixin;

import dev.bastionclaims.BastionClaims;
import dev.bastionclaims.core.ProtectionService;
import dev.bastionclaims.model.Claim;
import dev.bastionclaims.util.Fmt;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.block.BlockState;
import net.minecraft.block.FluidDrainable;
import net.minecraft.entity.LivingEntity;
import net.minecraft.fluid.Fluid;
import net.minecraft.item.BucketItem;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Stops non-members from spilling or scooping liquids inside a claim.
 *
 * <p>The event-based protection ({@code UseBlockCallback}) cannot reliably stop
 * buckets: when the client predicts an item-use it sends a separate
 * {@code PlayerInteractItemC2SPacket}, so {@link net.minecraft.item.BucketItem}'s
 * own {@code use()} runs on the server and places the fluid even though the block
 * callback said "denied". Guarding the bucket's actual fluid methods closes that
 * hole at the source, independent of packet routing.
 */
@Mixin(BucketItem.class)
public abstract class BucketItemMixin {

    @Shadow public abstract Fluid getFluid();

    /**
     * Emptying a full bucket ({@code placeFluid} receives the exact target cell).
     * If a non-member would place a liquid source inside a protected claim, cancel
     * it and keep the bucket full.
     */
    @Inject(method = "placeFluid", at = @At("HEAD"), cancellable = true)
    private void bastionclaims$protectPlace(LivingEntity placer, World world, BlockPos pos,
                                            BlockHitResult hitResult, CallbackInfoReturnable<Boolean> cir) {
        if (world.isClient() || !(placer instanceof ServerPlayerEntity p)) return;
        Claim c = ProtectionService.blockingBuild(p, dim(world), pos.getX(), pos.getY(), pos.getZ());
        if (c != null) {
            notify(p, c);
            cir.setReturnValue(false); // not placed → bucket stays full
        }
    }

    /**
     * Scooping a fluid source with an empty bucket removes a block from the claim,
     * which the break event never sees. Deny it when the targeted source sits in a
     * protected claim the player cannot build in.
     */
    /**
     * Guarded at the exact drain call rather than by a second raycast: vanilla
     * casts with SOURCE_ONLY (it passes through flowing water to the source
     * behind it), a separate raycast with ANY stops at the first flowing cell
     * — and a source just inside a border was scoopable through the flow in
     * front of it. Wrapping the drain gets the very position vanilla drains.
     */
    @WrapOperation(method = "use", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/block/FluidDrainable;tryDrainFluid(Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/world/WorldAccess;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;)Lnet/minecraft/item/ItemStack;"))
    private ItemStack bastionclaims$protectScoop(FluidDrainable drainable, LivingEntity user, WorldAccess world,
                                                 BlockPos pos, BlockState state, Operation<ItemStack> original) {
        if (user instanceof ServerPlayerEntity p && world instanceof World w && !w.isClient()) {
            Claim c = ProtectionService.blockingBuild(p, dim(w), pos.getX(), pos.getY(), pos.getZ());
            if (c != null) {
                notify(p, c);
                return ItemStack.EMPTY;   // "nothing drained" — bucket stays empty, block stays
            }
        }
        return original.call(drainable, user, world, pos, state);
    }

    private static void notify(ServerPlayerEntity p, Claim c) {
        ProtectionService.notifyThrottled(p, Fmt.text(BastionClaims.config().message("protect.deniedFluid", c.owner)));
    }

    private static String dim(World world) {
        return world.getRegistryKey().getValue().toString();
    }
}
