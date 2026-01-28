package dev.jacobwasbeast.interaction;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.event.events.ecs.BreakBlockEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
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
            int count = Math.max(0, group.sizeX * group.sizeY * group.sizeZ);
            if (count > 0) {
                // Determine which item to drop based on the group's blockId
                // Note: PANEL_INVISIBLE_BLOCK_ID drops as PANEL_BLOCK_ID since invisible is now a UI option
                String dropItemId = ImageFrameRuntimeManager.BASE_BLOCK_ID;
                if (group.blockId != null) {
                    if (ImageFrameRuntimeManager.SLIM_BLOCK_ID.equals(group.blockId)) {
                        dropItemId = ImageFrameRuntimeManager.SLIM_BLOCK_ID;
                    } else if (ImageFrameRuntimeManager.PANEL_BLOCK_ID.equals(group.blockId)
                            || ImageFrameRuntimeManager.PANEL_INVISIBLE_BLOCK_ID.equals(group.blockId)) {
                        dropItemId = ImageFrameRuntimeManager.PANEL_BLOCK_ID;
                    }
                }

                Vector3d dropPos = new Vector3d(
                        event.getTargetBlock().getX() + 0.5,
                        event.getTargetBlock().getY() + 0.5,
                        event.getTargetBlock().getZ() + 0.5);
                int remaining = count;
                while (remaining > 0) {
                    int qty = Math.min(remaining, 64);
                    ItemStack stack = new ItemStack(dropItemId, qty, null);
                    var holder = ItemComponent.generateItemDrop(store, stack, dropPos, Vector3f.ZERO, 0.0F, 0.0F, 0.0F);
                    if (holder != null) {
                        commandBuffer.addEntity(holder, AddReason.SPAWN);
                    }
                    remaining -= qty;
                }
            }
            event.setCancelled(true);
            plugin.getRuntimeManager().removeGroupAndAssets(world, group);
        }
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return Archetype.empty();
    }

    private boolean isFrameBlock(String blockId) {
        return ImageFrameRuntimeManager.BASE_BLOCK_ID.equals(blockId)
                || ImageFrameRuntimeManager.SLIM_BLOCK_ID.equals(blockId)
                || ImageFrameRuntimeManager.PANEL_BLOCK_ID.equals(blockId)
                || ImageFrameRuntimeManager.PANEL_INVISIBLE_BLOCK_ID.equals(blockId)
                || (blockId != null && blockId.startsWith(ImageFrameRuntimeManager.TILE_PREFIX));
    }
}
