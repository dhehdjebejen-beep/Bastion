package dev.bastionauth.mixin;

import net.minecraft.network.ClientConnection;
import net.minecraft.server.network.ServerCommonNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the connection object so join can claim what the handshake/login phases parked for it. */
@Mixin(ServerCommonNetworkHandler.class)
public interface ServerCommonNetworkHandlerAccessor {
    @Accessor("connection")
    ClientConnection bastionauth$connection();
}
