package dev.bastionac.mixin;

import dev.bastionac.BastionAC;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerChunkLoadingManager;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The Nether ends in fog at 96 blocks (the dimension's fog end distance;
 * the biomes only tint it), yet the server sends every chunk — and every
 * player and mob in it — out to the full view distance. A client that
 * strips the fog, or an ESP, sees bases and players far past what anyone
 * can see. Capping a Nether player's chunk radius at the fog (6 chunks;
 * vanilla still sends one ring beyond, so the client builds the edge and
 * covers the full 96 blocks from anywhere in its chunk) costs nothing: fewer
 * chunks go out, and entity tracking follows the same radius (3.3.1).
 */
@Mixin(ServerChunkLoadingManager.class)
public abstract class NetherViewDistanceMixin {

    @Inject(method = "getViewDistance(Lnet/minecraft/server/network/ServerPlayerEntity;)I", at = @At("RETURN"), cancellable = true)
    private void bastionac$netherFog(ServerPlayerEntity player, CallbackInfoReturnable<Integer> cir) {
        if (BastionAC.config() == null) return;
        int cap = BastionAC.config().general.netherViewDistance;
        if (cap <= 0 || player.getEntityWorld().getRegistryKey() != World.NETHER) return;
        if (cir.getReturnValueI() > cap) cir.setReturnValue(cap);
    }
}
