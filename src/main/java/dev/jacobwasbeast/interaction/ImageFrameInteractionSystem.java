package dev.jacobwasbeast.interaction;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.UseBlockEvent;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.jacobwasbeast.ImageFramesPlugin;
import dev.jacobwasbeast.runtime.ImageFrameRuntimeManager;
import dev.jacobwasbeast.ui.ImageFrameConfigPage;
import javax.annotation.Nonnull;

public class ImageFrameInteractionSystem extends EntityEventSystem<EntityStore, UseBlockEvent.Pre> {
    private final ImageFramesPlugin plugin;

    public ImageFrameInteractionSystem(ImageFramesPlugin plugin) {
        super(UseBlockEvent.Pre.class);
        this.plugin = plugin;
    }

    @Override
    public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull UseBlockEvent.Pre event) {
        if (event.getInteractionType() != InteractionType.Secondary
                && event.getInteractionType() != InteractionType.Use) {
            return;
        }
        BlockType blockType = event.getBlockType();
        if (blockType == null) {
            return;
        }
        String blockId = blockType.getId();
        if (!isFrameBlock(blockId)) {
            return;
        }

        var ref = archetypeChunk.getReferenceTo(index);
        Player player = store.getComponent(ref, Player.getComponentType());
        PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
        if (player == null || playerRef == null) {
            return;
        }

        var world = store.getExternalData().getWorld();
        var group = plugin.getStore().getGroupByPos(world.getName(), event.getTargetBlock());
        if (group != null && plugin.getConfig().isOwnerLockEnabled() && group.ownerUuid != null
                && !group.ownerUuid.equals(playerRef.getUuid().toString())) {
            playerRef.sendMessage(Message.raw("This ImageFrame is locked by another player."));
            return;
        }

        player.getPageManager().openCustomPage(ref, store,
                new ImageFrameConfigPage(plugin, playerRef, event.getTargetBlock(), event.getContext()));
        event.setCancelled(true);
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return Query.and(Player.getComponentType(), PlayerRef.getComponentType());
    }

    private boolean isFrameBlock(String blockId) {
        return ImageFrameRuntimeManager.isFrameBlockId(blockId);
    }
}
