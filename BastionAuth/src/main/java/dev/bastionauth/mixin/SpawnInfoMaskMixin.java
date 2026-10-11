package dev.bastionauth.mixin;

import dev.bastionauth.core.StateMask;
import net.minecraft.network.packet.s2c.play.CommonPlayerSpawnInfo;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.GameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * The join and respawn packets carry the game mode and the last death
 * location. Before the password both are the account's business only: the
 * login screen sees spectator (or survival while the agreement is owed) and
 * no death point (see {@link StateMask}, {@link dev.bastionauth.core.SpectatorGate}).
 */
@Mixin(ServerPlayerEntity.class)
public abstract class SpawnInfoMaskMixin {

    @Inject(method = "createCommonPlayerSpawnInfo", at = @At("RETURN"), cancellable = true)
    private void bastionauth$maskSpawnInfo(ServerWorld world, CallbackInfoReturnable<CommonPlayerSpawnInfo> cir) {
        ServerPlayerEntity self = (ServerPlayerEntity) (Object) this;
        if (!StateMask.masked(self)) return;
        CommonPlayerSpawnInfo i = cir.getReturnValue();
        if (i == null) return;
        cir.setReturnValue(new CommonPlayerSpawnInfo(i.dimensionType(), i.dimension(), i.seed(),
                dev.bastionauth.core.SpectatorGate.active(self) ? GameMode.SPECTATOR : GameMode.SURVIVAL,
                null, i.isDebug(), i.isFlat(), Optional.empty(),
                i.portalCooldown(), i.seaLevel()));
    }
}
