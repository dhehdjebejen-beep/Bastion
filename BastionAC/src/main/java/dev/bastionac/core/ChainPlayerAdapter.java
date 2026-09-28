package dev.bastionac.core;

import net.minecraft.server.network.ServerPlayerEntity;

import java.util.UUID;

/** Bridges a live player into the Minecraft-free chain-detector interface. */
public final class ChainPlayerAdapter implements AttackChainDetector.ServerPlayerEntityLike {

    private final UUID uuid;
    private final String name;

    public ChainPlayerAdapter(ServerPlayerEntity player) {
        this.uuid = player.getUuid();
        this.name = player.getGameProfile().name();
    }

    @Override
    public UUID uuid() {
        return uuid;
    }

    @Override
    public String name() {
        return name;
    }
}
