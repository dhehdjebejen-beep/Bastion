package dev.bastionclaims.core;

import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Reflection bridge to BastionAuth's codeword: a citizen who set one must
 * speak it before giving a claim away or deleting it. Without BastionAuth
 * the answer is always "not protected" — an absent mod never blocks a
 * citizen from their own land.
 *
 * <p>With BastionAuth installed but the bridge broken (a renamed method after
 * an update, an exception inside it) the answer used to be "not protected"
 * too — silently, so a codeword quietly stopped guarding anything. Now a broken
 * bridge fails closed (the codeword counts as required; operators still act
 * through {@code /claims}) and says so in the log.
 */
public final class AuthBridge {

    private static volatile boolean resolved;
    private static volatile Method protects;
    /** BastionAuth is installed, so a missing or failing bridge must not read as "no codeword". */
    private static volatile boolean authPresent;
    private static volatile boolean warned;

    private static void resolve() {
        if (resolved) return;
        resolved = true;
        if (!FabricLoader.getInstance().isModLoaded("bastionauth")) return;
        authPresent = true;
        try {
            protects = Class.forName("dev.bastionauth.BastionAuth").getMethod("codewordProtects", UUID.class);
        } catch (ReflectiveOperationException e) {
            protects = null;
            broken(e);
        }
    }

    private static void broken(Throwable t) {
        if (warned) return;
        warned = true;
        dev.bastionclaims.BastionClaims.LOGGER.error("BastionAuth is installed but its codeword bridge is broken ({}); "
                + "claim transfer and deletion now require the codeword for everyone until the mods are "
                + "updated together. Operators can still use /claims.", t.toString());
    }

    /** True when the account has a codeword that has not been spoken lately. */
    public static boolean codewordProtects(UUID id) {
        resolve();
        if (id == null || !authPresent) return false;
        if (protects == null) return true;          // installed but unreachable: fail closed
        try {
            return Boolean.TRUE.equals(protects.invoke(null, id));
        } catch (ReflectiveOperationException | RuntimeException e) {
            broken(e);
            return true;
        }
    }

    private AuthBridge() {}
}
