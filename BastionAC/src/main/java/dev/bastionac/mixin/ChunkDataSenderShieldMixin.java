package dev.bastionac.mixin;

import dev.bastionac.core.OreShield;
import net.minecraft.server.network.ChunkDataSender;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla builds every chunk packet for one player here. The ore shield
 * (3.6) needs to know which player, to keep far storage out of it.
 */
@Mixin(ChunkDataSender.class)
public abstract class ChunkDataSenderShieldMixin {

    @Inject(method = "sendChunkData", at = @At("HEAD"))
    private static void bastionac$begin(ServerPlayNetworkHandler handler, ServerWorld world, WorldChunk chunk, CallbackInfo ci) {
        OreShield.beginChunkSend(handler.player);
    }

    @Inject(method = "sendChunkData", at = @At("RETURN"))
    private static void bastionac$end(ServerPlayNetworkHandler handler, ServerWorld world, WorldChunk chunk, CallbackInfo ci) {
        OreShield.endChunkSend();
    }

    @Inject(method = "unload", at = @At("HEAD"))
    private void bastionac$unload(ServerPlayerEntity player, ChunkPos pos, CallbackInfo ci) {
        OreShield.onChunkUnload(player, pos);
    }
}
