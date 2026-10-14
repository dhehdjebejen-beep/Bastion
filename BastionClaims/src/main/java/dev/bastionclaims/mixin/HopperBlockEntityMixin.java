package dev.bastionclaims.mixin;

import dev.bastionclaims.core.ProtectionService;
import net.minecraft.block.AbstractRailBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.HopperBlock;
import net.minecraft.block.entity.Hopper;
import net.minecraft.block.entity.HopperBlockEntity;
import net.minecraft.entity.vehicle.HopperMinecartEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Isolates claim containers from external automation: a hopper or hopper-minecart
 * standing outside a claim cannot pull items from — or push items into — a
 * container inside it. This closes the "hopper-minecart under a chest steals the
 * loot" attack. Automation that lives entirely inside the owner's own claim is
 * unaffected.
 */
@Mixin(HopperBlockEntity.class)
public abstract class HopperBlockEntityMixin {

    /** The inventory a hopper/minecart pulls FROM (the block above it). */
    @Inject(method = "getInputInventory", at = @At("HEAD"), cancellable = true)
    private static void bastionclaims$guardInput(World world, Hopper hopper, BlockPos pos, BlockState state,
                                                 CallbackInfoReturnable<Inventory> cir) {
        if (world.isClient()) return;
        BlockPos hopperPos = BlockPos.ofFloored(hopper.getHopperX(), hopper.getHopperY(), hopper.getHopperZ());
        // A hopper minecart is a free-moving entity: anyone may walk into a claim
        // and shove one (placed outside) under a chest, where "it is inside the
        // claim" would wrongly make it the owner's automation. A cart only counts
        // as the owner's when it rides a rail — and strangers cannot lay rails in
        // someone's claim.
        boolean looseCart = hopper instanceof HopperMinecartEntity cart
                && !(world.getBlockState(cart.getBlockPos()).getBlock() instanceof AbstractRailBlock);
        if (looseCart ? ProtectionService.hopperBlockedForeign(dim(world), pos.getX(), pos.getY(), pos.getZ())
                : ProtectionService.hopperBlocked(dim(world), pos.getX(), pos.getY(), pos.getZ(),
                        hopperPos.getX(), hopperPos.getY(), hopperPos.getZ())) {
            cir.setReturnValue(null);
        }
    }

    /** The inventory a hopper block pushes INTO (the block it faces). */
    @Inject(method = "getOutputInventory", at = @At("HEAD"), cancellable = true)
    private static void bastionclaims$guardOutput(World world, BlockPos pos, HopperBlockEntity blockEntity,
                                                  CallbackInfoReturnable<Inventory> cir) {
        if (world.isClient()) return;
        BlockState state = world.getBlockState(pos);
        if (!state.contains(HopperBlock.FACING)) return;
        BlockPos target = pos.offset(state.get(HopperBlock.FACING));
        if (ProtectionService.hopperBlocked(dim(world), target.getX(), target.getY(), target.getZ(),
                pos.getX(), pos.getY(), pos.getZ())) {
            cir.setReturnValue(null);
        }
    }

    private static String dim(World world) {
        return world.getRegistryKey().getValue().toString();
    }
}
