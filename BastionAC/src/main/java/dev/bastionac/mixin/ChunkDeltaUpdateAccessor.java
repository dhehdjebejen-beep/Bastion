package dev.bastionac.mixin;

import net.minecraft.block.BlockState;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.util.math.ChunkSectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The section update's arrays, for the ore shield's filter (3.6). */
@Mixin(ChunkDeltaUpdateS2CPacket.class)
public interface ChunkDeltaUpdateAccessor {

    @Accessor("sectionPos")
    ChunkSectionPos bastionac$sectionPos();

    @Accessor("positions")
    short[] bastionac$positions();

    @Accessor("blockStates")
    BlockState[] bastionac$states();
}
