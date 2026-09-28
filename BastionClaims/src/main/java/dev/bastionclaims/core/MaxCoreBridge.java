package dev.bastionclaims.core;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import java.lang.reflect.Method;

/**
 * Reflection bridge to MaxCore's build mode.
 *
 * <p>Builders are trusted citizens, not operators, so WorldEdit opens up for
 * them — under exactly the same block rules as their creative menu. Resolved
 * reflectively so this mod still runs on a server without MaxCore, where build
 * mode simply does not exist and WorldEdit stays operator-only.
 */
public final class MaxCoreBridge {

    private static volatile boolean resolved;
    private static volatile Method isActive;
    private static volatile Method allowedBlockId;

    private static void resolve() {
        if (resolved) return;
        resolved = true;
        if (!FabricLoader.getInstance().isModLoaded("maxcore")) return;
        try {
            isActive = Class.forName("dev.maxcore.manager.BuildModeManager")
                    .getMethod("isActive", ServerPlayerEntity.class);
            allowedBlockId = Class.forName("dev.maxcore.manager.CreativeRules")
                    .getMethod("allowedBlockId", String.class);
        } catch (ReflectiveOperationException e) {
            isActive = null;
            allowedBlockId = null;
        }
    }

    /**
     * One line about MaxCore's state for the access report. The claim mod can
     * say "allowed" perfectly correctly while build mode blocks the click a
     * moment later, and from the player's chair those look identical.
     */
    public static String stateLine(ServerPlayerEntity p) {
        resolve();
        if (isActive == null) return "&8Режим стройки: мод MaxCore не отвечает";
        return isBuilding(p)
                ? "&e⚠ Режим стройки: &cВКЛ&e — хранилища и сундуки закрывает ОН, не приват"
                : "&7Режим стройки: &aВЫКЛ";
    }

    /** Whether this player is currently in MaxCore's build mode. */
    public static boolean isBuilding(ServerPlayerEntity p) {
        resolve();
        if (isActive == null || p == null) return false;
        try {
            return Boolean.TRUE.equals(isActive.invoke(null, p));
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    /**
     * Whether a builder may use this block. Always true for anyone who is not
     * in build mode — operators keep unrestricted WorldEdit.
     */
    public static boolean mayUseBlock(ServerPlayerEntity p, BlockState state) {
        if (!isBuilding(p)) return true;
        if (allowedBlockId == null) return false;
        Identifier id = Registries.BLOCK.getId(state.getBlock());
        if (id == null) return false;
        try {
            return Boolean.TRUE.equals(allowedBlockId.invoke(null, id.toString()));
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    private MaxCoreBridge() {}
}
