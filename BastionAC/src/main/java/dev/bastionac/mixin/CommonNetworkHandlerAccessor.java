package dev.bastionac.mixin;

import net.minecraft.server.network.ServerCommonNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The keep-alive vanilla is waiting for: lets the telemetry measure each round trip raw. */
@Mixin(ServerCommonNetworkHandler.class)
public interface CommonNetworkHandlerAccessor {

    @Accessor("lastKeepAliveTime")
    long bastionac$lastKeepAliveTime();

    @Accessor("keepAliveId")
    long bastionac$keepAliveId();

    @Accessor("waitingForKeepAlive")
    boolean bastionac$waitingForKeepAlive();

    /** The connection: one object per client from configuration to the last play packet. */
    @Accessor("connection")
    net.minecraft.network.ClientConnection bastionac$connection();
}
