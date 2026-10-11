package dev.bastionauth.mixin;

import net.minecraft.server.network.ServerPlayerInteractionManager;
import net.minecraft.world.GameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * The game mode without a single packet: {@link dev.bastionauth.core.SpectatorGate}
 * switches it before the join packet exists and around every save.
 */
@Mixin(ServerPlayerInteractionManager.class)
public interface InteractionManagerInvoker {

    @Invoker("setGameMode")
    void bastionauth$setGameMode(GameMode gameMode, GameMode previousGameMode);
}
