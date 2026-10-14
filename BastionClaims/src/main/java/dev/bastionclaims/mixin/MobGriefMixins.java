package dev.bastionclaims.mixin;

import dev.bastionclaims.core.ProtectionService;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Mobs that change blocks without exploding. Each of them consults the
 * global {@code mobGriefing} rule, which knows nothing about claims, so each
 * gets its own guard on the exact call that changes the world. All follow the
 * claim's mob-grief flag ({@code /claim flag mobs}).
 */
public final class MobGriefMixins {

    private MobGriefMixins() {}

    /** An enderman lifting a block out of a claim. */
    @Mixin(targets = "net.minecraft.entity.mob.EndermanEntity$PickUpBlockGoal")
    public abstract static class EndermanPickUp {
        @Redirect(method = "tick", at = @At(value = "INVOKE",
                target = "Lnet/minecraft/world/World;removeBlock(Lnet/minecraft/util/math/BlockPos;Z)Z"))
        private boolean bastionclaims$guard(World world, BlockPos pos, boolean move) {
            if (ProtectionService.mobGriefBlockedAt(world, pos)) return false;
            return world.removeBlock(pos, move);
        }
    }

    /** An enderman dropping its block into a claim. */
    @Mixin(targets = "net.minecraft.entity.mob.EndermanEntity$PlaceBlockGoal")
    public abstract static class EndermanPlace {
        @Redirect(method = "tick", at = @At(value = "INVOKE",
                target = "Lnet/minecraft/world/World;setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;I)Z"))
        private boolean bastionclaims$guard(World world, BlockPos pos, BlockState state, int flags) {
            if (ProtectionService.mobGriefBlockedAt(world, pos)) return false;
            return world.setBlockState(pos, state, flags);
        }
    }

    /** The wither tearing through blocks after it is hit (not its skulls — those are explosions). */
    @Mixin(net.minecraft.entity.boss.WitherEntity.class)
    public abstract static class Wither {
        @Redirect(method = "mobTick", at = @At(value = "INVOKE",
                target = "Lnet/minecraft/server/world/ServerWorld;breakBlock(Lnet/minecraft/util/math/BlockPos;ZLnet/minecraft/entity/Entity;)Z"))
        private boolean bastionclaims$guard(ServerWorld world, BlockPos pos, boolean drop, Entity breaker) {
            if (ProtectionService.mobGriefBlockedAt(world, pos)) return false;
            return world.breakBlock(pos, drop, breaker);
        }
    }

    /** A ravager trampling through leaves and crops. */
    @Mixin(net.minecraft.entity.mob.RavagerEntity.class)
    public abstract static class Ravager {
        @Redirect(method = "tickMovement", at = @At(value = "INVOKE",
                target = "Lnet/minecraft/server/world/ServerWorld;breakBlock(Lnet/minecraft/util/math/BlockPos;ZLnet/minecraft/entity/Entity;)Z"))
        private boolean bastionclaims$guard(ServerWorld world, BlockPos pos, boolean drop, Entity breaker) {
            if (ProtectionService.mobGriefBlockedAt(world, pos)) return false;
            return world.breakBlock(pos, drop, breaker);
        }
    }

    /** The ender dragon eating through whatever it flies into. */
    @Mixin(net.minecraft.entity.boss.dragon.EnderDragonEntity.class)
    public abstract static class Dragon {
        @Redirect(method = "destroyBlocks", at = @At(value = "INVOKE",
                target = "Lnet/minecraft/server/world/ServerWorld;removeBlock(Lnet/minecraft/util/math/BlockPos;Z)Z"))
        private boolean bastionclaims$guard(ServerWorld world, BlockPos pos, boolean move) {
            if (ProtectionService.mobGriefBlockedAt(world, pos)) return false;
            return world.removeBlock(pos, move);
        }
    }

    /** A zombie (or vindicator) breaking down a door. */
    @Mixin(net.minecraft.entity.ai.goal.BreakDoorGoal.class)
    public abstract static class BreakDoor {
        @Redirect(method = "tick", at = @At(value = "INVOKE",
                target = "Lnet/minecraft/world/World;removeBlock(Lnet/minecraft/util/math/BlockPos;Z)Z"))
        private boolean bastionclaims$guard(World world, BlockPos pos, boolean move) {
            if (ProtectionService.mobGriefBlockedAt(world, pos)) return false;
            return world.removeBlock(pos, move);
        }
    }

    /** A zombie stamping out turtle eggs. */
    @Mixin(net.minecraft.entity.ai.goal.StepAndDestroyBlockGoal.class)
    public abstract static class StepAndDestroy {
        @Redirect(method = "tick", at = @At(value = "INVOKE",
                target = "Lnet/minecraft/world/World;removeBlock(Lnet/minecraft/util/math/BlockPos;Z)Z"))
        private boolean bastionclaims$guard(World world, BlockPos pos, boolean move) {
            if (ProtectionService.mobGriefBlockedAt(world, pos)) return false;
            return world.removeBlock(pos, move);
        }
    }
}
