package dev.bastionauth.mixin;

import net.minecraft.server.network.ChunkDataSender;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every chunk a client receives goes through here. A client in limbo gets it
 * empty — see {@link dev.bastionauth.core.Limbo#sendEmptyChunk}.
 */
@Mixin(ChunkDataSender.class)
public abstract class LimboChunkMixin {

    @Inject(method = "sendChunkData", at = @At("HEAD"), cancellable = true)
    private static void bastionauth$emptyInLimbo(ServerPlayNetworkHandler handler, ServerWorld world, WorldChunk chunk,
                                                 CallbackInfo ci) {
        if (dev.bastionauth.core.Limbo.sendEmptyChunk(handler, world, chunk)) ci.cancel();
    }
}
