package dev.bastionclaims.util;

import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;

/** Dimension id → world lookup, shared by claims and WorldEdit. */
public final class Worlds {

    /** The world for {@code dim}, or {@code null} when it no longer exists. */
    public static ServerWorld resolve(MinecraftServer server, String dim) {
        if (server == null || dim == null) return null;
        try {
            return server.getWorld(RegistryKey.of(RegistryKeys.WORLD, Identifier.of(dim)));
        } catch (Exception ignored) {
            return null;
        }
    }

    /** As {@link #resolve}, falling back to the overworld instead of null. */
    public static ServerWorld resolveOrOverworld(MinecraftServer server, String dim) {
        ServerWorld w = resolve(server, dim);
        return w != null ? w : server.getOverworld();
    }

    private Worlds() {}
}
