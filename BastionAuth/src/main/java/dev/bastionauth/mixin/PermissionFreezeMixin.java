package dev.bastionauth.mixin;

import dev.bastionauth.BastionAuth;
import net.minecraft.command.permission.PermissionPredicate;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * An operator is an operator by UUID, and in offline mode a UUID is a name —
 * so an impostor joining under an operator's name holds level-4 permissions from
 * the first tick, password or not. Every mod (and vanilla) that asks
 * {@code getPermissions()} would treat that connection as staff: BastionAC
 * would send it alerts with player coordinates, claims would let it bypass
 * protection, vanilla would honour its NBT queries.
 *
 * <p>Until the password is verified the answer is {@link PermissionPredicate#NONE}.
 * {@code finishAuth} re-sends the command tree so the real operator gets
 * their commands back the moment they log in.
 */
@Mixin(ServerPlayerEntity.class)
public abstract class PermissionFreezeMixin {

    @Inject(method = "getPermissions", at = @At("HEAD"), cancellable = true)
    private void bastionauth$noPermissionsWhileFrozen(CallbackInfoReturnable<PermissionPredicate> cir) {
        if (BastionAuth.isBlocked((ServerPlayerEntity) (Object) this)) {
            cir.setReturnValue(PermissionPredicate.NONE);
        }
    }
}
