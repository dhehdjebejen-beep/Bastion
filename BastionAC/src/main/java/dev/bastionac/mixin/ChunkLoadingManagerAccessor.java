package dev.bastionac.mixin;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.server.world.ServerChunkLoadingManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Vanilla's entity trackers by entity id, for the ESP shield's re-judging (3.6). */
@Mixin(ServerChunkLoadingManager.class)
public interface ChunkLoadingManagerAccessor {

    @Accessor("entityTrackers")
    Int2ObjectMap<?> bastionac$entityTrackers();
}
