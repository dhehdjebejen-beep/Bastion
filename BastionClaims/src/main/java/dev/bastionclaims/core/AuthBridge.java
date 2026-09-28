package dev.bastionclaims.core;

import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Reflection bridge to BastionAuth's codeword: a citizen who set one must
 * speak it before giving a claim away or deleting it. Without BastionAuth
 * the answer is always "not protected" — an absent mod never blocks a
 * citizen from their own land.
 */
public final class AuthBridge {

    private static volatile boolean resolved;
    private static volatile Method protects;

    private static void resolve() {
        if (resolved) return;
        resolved = true;
        if (!FabricLoader.getInstance().isModLoaded("bastionauth")) return;
        try {
            protects = Class.forName("dev.bastionauth.BastionAuth").getMethod("codewordProtects", UUID.class);
        } catch (ReflectiveOperationException e) {
            protects = null;
        }
    }

    /** True when the account has a codeword that has not been spoken lately. */
    public static boolean codewordProtects(UUID id) {
        resolve();
        if (protects == null || id == null) return false;
        try {
            return Boolean.TRUE.equals(protects.invoke(null, id));
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    private AuthBridge() {}
}
