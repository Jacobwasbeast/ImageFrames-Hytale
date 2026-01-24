package dev.jacobwasbeast.interaction;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.event.events.ecs.BreakBlockEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.jacobwasbeast.ImageFramesPlugin;
import dev.jacobwasbeast.runtime.ImageFrameRuntimeManager;
import javax.annotation.Nonnull;

public class ImageFrameBreakSystem extends EntityEventSystem<EntityStore, BreakBlockEvent> {
    private final ImageFramesPlugin plugin;

    public ImageFrameBreakSystem(ImageFramesPlugin plugin) {
        super(BreakBlockEvent.class);
        this.plugin = plugin;
    }

    @Override
    public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull BreakBlockEvent event) {
        BlockType blockType = event.getBlockType();
        if (blockType == null) {
            return;
        }
        String blockId = blockType.getId();
        if (!isFrameBlock(blockId)) {
            return;
        }

        var world = store.getExternalData().getWorld();
        var group = plugin.getStore().getGroupByPos(world.getName(), event.getTargetBlock());
        if (group != null) {
            plugin.getStore().removeGroup(group.groupId);
        }
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return Query.and(PlayerRef.getComponentType());
    }

    private boolean isFrameBlock(String blockId) {
        return ImageFrameRuntimeManager.BASE_BLOCK_ID.equals(blockId)
                || (blockId != null && blockId.startsWith(ImageFrameRuntimeManager.TILE_PREFIX));
    }
}
