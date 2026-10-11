package dev.bastionac.mixin;

import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Vanilla's own teleport handshake. The anti-cheat used to guess when a
 * teleport had been accepted from how close the next move packet landed to
 * the target — and a packet the client sent <em>before</em> it saw a short
 * teleport lands close enough. Reading the id vanilla actually waits for
 * removes the guess.
 */
@Mixin(ServerPlayNetworkHandler.class)
public interface PlayNetworkHandlerAccessor {

    /** Where vanilla is waiting for the client to confirm it is; null once confirmed. */
    @Accessor("requestedTeleportPos")
    Vec3d bastionac$requestedTeleportPos();

    /** The id of the last teleport vanilla sent. */
    @Accessor("requestedTeleportId")
    int bastionac$requestedTeleportId();
}
