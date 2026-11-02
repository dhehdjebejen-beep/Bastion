package dev.bastionac.mixin;

import dev.bastionac.core.OreShield;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.network.packet.s2c.play.ChunkData;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Map;

/**
 * The chunk packet is written from a disguised copy of the sections (3.6,
 * {@link OreShield}). Size and bytes are both taken from that copy: the
 * buffer is allocated to the measured size and vanilla throws if they differ.
 * Block entities whose block goes out disguised are left out.
 */
@Mixin(ChunkData.class)
public abstract class ChunkDataShieldMixin {

    @Redirect(method = "getSectionsPacketSize", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/chunk/WorldChunk;getSectionArray()[Lnet/minecraft/world/chunk/ChunkSection;"))
    private static ChunkSection[] bastionac$sizeSections(WorldChunk chunk) {
        return OreShield.sectionsForSize(chunk);
    }

    @Redirect(method = "writeSections", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/chunk/WorldChunk;getSectionArray()[Lnet/minecraft/world/chunk/ChunkSection;"))
    private static ChunkSection[] bastionac$writeSections(WorldChunk chunk) {
        return OreShield.sectionsForWrite(chunk);
    }

    @Redirect(method = "<init>(Lnet/minecraft/world/chunk/WorldChunk;)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/chunk/WorldChunk;getBlockEntities()Ljava/util/Map;"))
    private Map<BlockPos, BlockEntity> bastionac$blockEntities(WorldChunk chunk) {
        return OreShield.blockEntities(chunk, chunk.getBlockEntities());
    }
}
