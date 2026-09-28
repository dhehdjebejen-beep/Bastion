package dev.bastionclaims;

import dev.bastionclaims.command.ClaimAdminCommands;
import dev.bastionclaims.command.ClaimCommands;
import dev.bastionclaims.command.WorldEditCommands;
import dev.bastionclaims.config.ClaimConfig;
import dev.bastionclaims.core.ClaimEvents;
import dev.bastionclaims.core.ClaimManager;
import dev.bastionclaims.core.ProtectionService;
import dev.bastionclaims.core.WorldEdit;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Entry point (dedicated servers). Loads config + claims, wires protection and
 * wand events, and registers the {@code /claim} and {@code /claims} commands.
 */
public final class BastionClaims implements DedicatedServerModInitializer {

    public static final String MOD_ID = "bastionclaims";
    public static final Logger LOGGER = LoggerFactory.getLogger("BastionClaims");

    private static volatile ClaimConfig config;
    private static volatile Path configFile;

    public static ClaimConfig config() {
        return config;
    }

    /** Reloads config.json in place (used by {@code /claims reload}). */
    public static void reloadConfig() throws IOException {
        if (configFile == null) return;
        config = ClaimConfig.loadOrCreate(configFile, w -> LOGGER.warn("{}", w));
    }

    @Override
    public void onInitializeServer() {
        LOGGER.info("BastionClaims loading...");

        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            try {
                Path dir = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
                Files.createDirectories(dir);
                configFile = dir.resolve("config.json");
                config = ClaimConfig.loadOrCreate(configFile, w -> LOGGER.warn("{}", w));
                ClaimManager.init(dir);
                LOGGER.info("BastionClaims ready ({} claim(s), {} tier(s))",
                        ClaimManager.all().size(), config.progression.tiers.size());
            } catch (Exception e) {
                // Claims is a protection boundary. Continuing with an empty or
                // unverified registry would expose every claimed block to grief.
                LOGGER.error("BastionClaims initialisation failed; refusing to start without verified claim data", e);
                throw new IllegalStateException("BastionClaims could not load verified protection data", e);
            }
        });

        ClaimEvents.register();

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            ClaimCommands.register(dispatcher);
            ClaimAdminCommands.register(dispatcher);
            WorldEditCommands.register(dispatcher);
        });

        // WorldEdit operations are spread over ticks instead of freezing the
        // server for one huge write.
        ServerTickEvents.END_SERVER_TICK.register(WorldEdit::tick);

        // Border crossings and the owner's outline particles.
        ServerTickEvents.END_SERVER_TICK.register(new ServerTickEvents.EndTick() {
            private long tick;

            @Override
            public void onEndTick(net.minecraft.server.MinecraftServer server) {
                dev.bastionclaims.core.ClaimPresence.tick(server, ++tick);
            }
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                WorldEdit.markOnline(handler.getPlayer().getUuid()));

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            java.util.UUID id = handler.getPlayer().getUuid();
            ProtectionService.forget(id);
            dev.bastionclaims.core.ClaimPresence.forget(id);
            dev.bastionclaims.core.ClaimEvents.forgetTrace(id);
            dev.bastionclaims.wand.ClaimWand.forget(id);
            // The selection, clipboard and undo history deliberately survive a
            // reconnect — losing a selection because the client dropped was one
            // of the most annoying things about the old behaviour. They are
            // released on a timer instead (worldEditSessionKeepMinutes); an undo
            // stack is tens of megabytes and used to be held forever.
            WorldEdit.markOffline(id);
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> ClaimManager.saveNow());

        LOGGER.info("BastionClaims loaded");
    }
}
