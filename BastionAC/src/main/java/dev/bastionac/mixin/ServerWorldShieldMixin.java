package dev.bastionac.mixin;

import dev.bastionac.core.OreShield;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every block change on the server passes here; one that opens a face
 * re-sends the hidden neighbours (3.6, {@link OreShield}). Only real changes
 * of the world reveal — never a packet from the client.
 */
@Mixin(ServerWorld.class)
public abstract class ServerWorldShieldMixin {

    // HEAD: the method returns early when the collision shape did not change,
    // and stone turning into glass is exactly such a change that opens a face.
    @Inject(method = "updateListeners", at = @At("HEAD"))
    private void bastionac$reveal(BlockPos pos, BlockState oldState, BlockState newState, int flags, CallbackInfo ci) {
        OreShield.onBlockChanged((ServerWorld) (Object) this, pos, oldState, newState);
    }
}
