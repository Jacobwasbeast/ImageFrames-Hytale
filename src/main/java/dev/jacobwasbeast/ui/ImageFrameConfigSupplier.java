package dev.jacobwasbeast.ui;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.player.pages.CustomUIPage;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.server.OpenCustomUIInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.protocol.BlockPosition;
import com.hypixel.hytale.math.vector.Vector3i;
import javax.annotation.Nullable;

public record ImageFrameConfigSupplier() implements OpenCustomUIInteraction.CustomPageSupplier {
    public static final Codec<ImageFrameConfigSupplier> CODEC = BuilderCodec
            .builder(ImageFrameConfigSupplier.class, ImageFrameConfigSupplier::new).build();

    @Override
    @Nullable
    public CustomUIPage tryCreate(Ref<EntityStore> ref, ComponentAccessor<EntityStore> componentAccessor,
            PlayerRef playerRef, InteractionContext context) {
        Vector3i target = null;
        if (context != null) {
            BlockPosition raw = context.getMetaStore().getIfPresentMetaObject(Interaction.TARGET_BLOCK_RAW);
            if (raw != null) {
                target = new Vector3i(raw.x, raw.y, raw.z);
            }
        }
        return new ImageFrameConfigPage(dev.jacobwasbeast.ImageFramesPlugin.getInstance(), playerRef, target, context);
    }
}
