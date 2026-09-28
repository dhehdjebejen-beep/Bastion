package dev.bastionac.mixin;

import dev.bastionac.BastionAC;
import dev.bastionac.core.PlayerData;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.GameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Stamps the tick of every game-mode change so PacketGuard can tell an
 * in-flight creative packet (the client did not know yet) from a crafted one.
 */
@Mixin(ServerPlayerEntity.class)
public abstract class GameModeChangeMixin {

    @Inject(method = "changeGameMode", at = @At("HEAD"))
    private void bastionac$noteGameModeChange(GameMode mode, CallbackInfoReturnable<Boolean> cir) {
        PlayerData d = BastionAC.data((ServerPlayerEntity) (Object) this);
        if (d != null) d.gameModeChangedTick = BastionAC.serverTick();
    }
}
