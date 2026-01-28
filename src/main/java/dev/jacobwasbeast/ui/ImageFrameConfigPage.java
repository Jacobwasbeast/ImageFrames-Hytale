package dev.jacobwasbeast.ui;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.math.vector.Vector4d;
import com.hypixel.hytale.protocol.BlockPosition;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.jacobwasbeast.ImageFramesPlugin;
import dev.jacobwasbeast.store.ImageFrameStore.FrameGroup;
import javax.annotation.Nonnull;

public class ImageFrameConfigPage extends InteractiveCustomUIPage<ImageFrameConfigPage.FramePageData> {
    private final ImageFramesPlugin plugin;
    private final PlayerRef playerRef;
    private final Vector3i blockPos;
    private final InteractionContext interactionContext;

    public ImageFrameConfigPage(ImageFramesPlugin plugin, PlayerRef playerRef, Vector3i blockPos,
            InteractionContext interactionContext) {
        super(playerRef, CustomPageLifetime.CanDismiss, FramePageData.CODEC);
        this.plugin = plugin;
        this.playerRef = playerRef;
        this.blockPos = blockPos;
        this.interactionContext = interactionContext;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder commandBuilder,
            @Nonnull UIEventBuilder eventBuilder, @Nonnull Store<EntityStore> store) {
        commandBuilder.append("Pages/ImageFrames/ImageFrameConfig.ui");

        var world = store.getExternalData().getWorld();
        FrameGroup group = null;
        if (blockPos != null && world != null) {
            group = plugin.getStore().getGroupByPos(world.getName(), blockPos);
        }

        if (group != null) {
            if (group.url != null) {
                commandBuilder.set("#UrlInput.Value", group.url);
            }
            if (group.fit != null) {
                commandBuilder.set("#FitDropdown.Value", group.fit);
            }
            commandBuilder.set("#RotationInput.Value", String.valueOf(group.rot));
            commandBuilder.set("#FlipXContainer #CheckBox.Value", group.flipX);
            commandBuilder.set("#FlipYContainer #CheckBox.Value", group.flipY);
            // Only show hideFrame checkbox for panels
            boolean isPanel = dev.jacobwasbeast.runtime.ImageFrameRuntimeManager.PANEL_BLOCK_ID.equals(group.blockId) 
                    || dev.jacobwasbeast.runtime.ImageFrameRuntimeManager.PANEL_INVISIBLE_BLOCK_ID.equals(group.blockId);
            if (isPanel) {
                commandBuilder.set("#HideFrameContainer.Visible", true);
                commandBuilder.set("#HideFrameContainer #CheckBox.Value", group.hideFrame);
            } else {
                commandBuilder.set("#HideFrameContainer.Visible", false);
            }
        } else {
            // Check if current block is a panel
            if (blockPos != null && world != null) {
                try {
                    var blockType = world.getBlockType(blockPos.getX(), blockPos.getY(), blockPos.getZ());
                    if (blockType != null) {
                        String blockId = blockType.getId();
                        boolean isPanel = dev.jacobwasbeast.runtime.ImageFrameRuntimeManager.PANEL_BLOCK_ID.equals(blockId) 
                                || dev.jacobwasbeast.runtime.ImageFrameRuntimeManager.PANEL_INVISIBLE_BLOCK_ID.equals(blockId);
                        commandBuilder.set("#HideFrameContainer.Visible", isPanel);
                        if (isPanel) {
                            commandBuilder.set("#HideFrameContainer #CheckBox.Value", 
                                    dev.jacobwasbeast.runtime.ImageFrameRuntimeManager.PANEL_INVISIBLE_BLOCK_ID.equals(blockId));
                        }
                    }
                } catch (Exception ignored) {
                    commandBuilder.set("#HideFrameContainer.Visible", false);
                }
            } else {
                commandBuilder.set("#HideFrameContainer.Visible", false);
            }
        }

        java.util.List<com.hypixel.hytale.server.core.ui.DropdownEntryInfo> fitEntries = new java.util.ArrayList<>();
        fitEntries.add(new com.hypixel.hytale.server.core.ui.DropdownEntryInfo(
                com.hypixel.hytale.server.core.ui.LocalizableString.fromMessageId("imageFrames.customUI.fitStretch"),
                "stretch"));
        fitEntries.add(new com.hypixel.hytale.server.core.ui.DropdownEntryInfo(
                com.hypixel.hytale.server.core.ui.LocalizableString.fromMessageId("imageFrames.customUI.fitCrop"),
                "crop"));
        fitEntries.add(new com.hypixel.hytale.server.core.ui.DropdownEntryInfo(
                com.hypixel.hytale.server.core.ui.LocalizableString.fromMessageId("imageFrames.customUI.fitContain"),
                "contain"));
        commandBuilder.set("#FitDropdown.Entries", fitEntries);
        if (group == null || group.fit == null || group.fit.isEmpty()) {
            commandBuilder.set("#FitDropdown.Value", "stretch");
        }

        addEventBindings(eventBuilder);
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
            @Nonnull FramePageData data) {
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            return;
        }

        if ("Cancel".equals(data.action)) {
            player.getPageManager().setPage(ref, store, Page.None);
            return;
        }

        if (!"Apply".equals(data.action)) {
            return;
        }

        String url = data.url != null ? data.url.trim() : "";
        String fit = data.fit != null && !data.fit.isEmpty() ? data.fit.trim() : "stretch";
        int rot = parseInt(data.rotation, 0);
        boolean flipX = data.flipX;
        boolean flipY = data.flipY;

        if (url.isEmpty()) {
            playerRef.sendMessage(Message.raw("URL is required."));
            return;
        }

        var world = store.getExternalData().getWorld();
        if (world == null || blockPos == null) {
            playerRef.sendMessage(Message.raw("Missing target block."));
            return;
        }

        final String finalUrl = url;
        final String finalFit = fit;
        final int finalRot = rot;
        final boolean finalFlipX = flipX;
        final boolean finalFlipY = flipY;
        store.getExternalData().getWorld().execute(() -> {
            FrameGroup existing = plugin.getStore().getGroupByPos(world.getName(), blockPos);
            String resolvedOwnerUuid = existing != null ? existing.ownerUuid : null;
            if (plugin.getConfig().isOwnerLockEnabled()) {
                if (resolvedOwnerUuid == null || resolvedOwnerUuid.isEmpty()) {
                    resolvedOwnerUuid = playerRef.getUuid().toString();
                } else if (!resolvedOwnerUuid.equals(playerRef.getUuid().toString())) {
                    playerRef.sendMessage(Message.raw("This ImageFrame is locked by another player."));
                    return;
                }
            }
            final String finalOwnerUuid = resolvedOwnerUuid;

            dev.jacobwasbeast.runtime.ImageFrameRuntimeManager.GroupInfo info;
            try {
                Vector4d hitLocation = null;
                Vector3i target = blockPos;
                if (interactionContext != null) {
                    hitLocation = interactionContext.getMetaStore().getIfPresentMetaObject(Interaction.HIT_LOCATION);
                    if (target == null) {
                        BlockPosition raw = interactionContext.getMetaStore()
                                .getIfPresentMetaObject(Interaction.TARGET_BLOCK_RAW);
                        if (raw != null) {
                            target = new Vector3i(raw.x, raw.y, raw.z);
                        }
                    }
                }
                dev.jacobwasbeast.runtime.ImageFrameRuntimeManager.Axis preferredAxis = dev.jacobwasbeast.runtime.ImageFrameRuntimeManager
                        .getAxisFromHit(hitLocation, target);

                if (preferredAxis == null && target != null) {
                    try {
                        var playerRefEntity = playerRef.getReference();
                        if (playerRefEntity != null && playerRefEntity.isValid()) {
                            var transform = store.getComponent(playerRefEntity, TransformComponent.getComponentType());
                            var headRotation = store.getComponent(playerRefEntity, HeadRotation.getComponentType());
                            if (transform != null && headRotation != null) {
                                Vector3d start = transform.getPosition();
                                // Approx eye height in Hytale is 1.62
                                Vector3d eyePos = new Vector3d(start.x, start.y + 1.62, start.z);

                                // Get direction from HeadRotation
                                Vector3d dir = headRotation.getDirection();

                                preferredAxis = dev.jacobwasbeast.runtime.ImageFrameRuntimeManager
                                        .getAxisFromRaycast(eyePos, dir, target);
                            }
                        }
                    } catch (Exception ignored) {
                        // Fallback failed, proceed without preferredAxis
                    }
                }

                info = plugin.getRuntimeManager().collectGroupInfo(world, blockPos, preferredAxis);
            } catch (Exception e) {
                playerRef.sendMessage(Message.raw("Invalid frame layout: " + e.getMessage()));
                return;
            }

            FrameGroup previousGroup = plugin.getStore().getGroupByPos(world.getName(), blockPos);
            // Read rotation from ORIGINAL blocks BEFORE placePlaceholder replaces them
            java.util.Map<com.hypixel.hytale.math.vector.Vector3i, Integer> originalRotations = 
                    plugin.getRuntimeManager().readOriginalRotations(world, info);
            plugin.getRuntimeManager().placePlaceholder(world, info, originalRotations);

            final String facing = resolveFacing(store, info);
            // Determine blockId: always use PANEL_BLOCK_ID for panels (not PANEL_INVISIBLE_BLOCK_ID)
            String blockId = info.blockId != null && !info.blockId.isEmpty() ? info.blockId : dev.jacobwasbeast.runtime.ImageFrameRuntimeManager.BASE_BLOCK_ID;
            // Convert PANEL_INVISIBLE_BLOCK_ID to PANEL_BLOCK_ID (invisible is now controlled by hideFrame)
            if (dev.jacobwasbeast.runtime.ImageFrameRuntimeManager.PANEL_INVISIBLE_BLOCK_ID.equals(blockId)) {
                blockId = dev.jacobwasbeast.runtime.ImageFrameRuntimeManager.PANEL_BLOCK_ID;
            }
            // Get hideFrame from UI (for panels)
            boolean hideFrame = false;
            if (dev.jacobwasbeast.runtime.ImageFrameRuntimeManager.PANEL_BLOCK_ID.equals(blockId)) {
                hideFrame = data.hideFrame;
            }
            final String finalBlockId = blockId;
            final boolean finalHideFrame = hideFrame;
            java.util.concurrent.CompletableFuture
                    .supplyAsync(() -> {
                        try {
                            return plugin.getRuntimeManager().buildGroupAssets(info, finalUrl, finalFit, finalRot,
                                    finalFlipX,
                                    finalFlipY, finalOwnerUuid, facing, finalBlockId, finalHideFrame);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .thenAccept(group -> {
                        // Broadcast assets (same flow as original frames)
                        plugin.getRuntimeManager().broadcastGroupAssets(group);
                        world.execute(() -> {
                            // Store rotations in group for use in applyGroup
                            group.originalRotations = originalRotations;
                            plugin.getRuntimeManager().applyGroupWhenReady(world, info, group, 20,
                                    () -> {
                                        playerRef.sendMessage(Message.raw("ImageFrame updated."));
                                        player.getPageManager().setPage(ref, store, Page.None);
                                    },
                                    () -> {
                                        if (previousGroup != null) {
                                            plugin.getRuntimeManager().applyGroup(world, info, previousGroup, previousGroup.originalRotations != null ? previousGroup.originalRotations : new java.util.HashMap<>());
                                        }
                                        playerRef.sendMessage(
                                                Message.raw("ImageFrame assets are still loading. Try again in a moment."));
                                    });
                        });
                    })
                    .exceptionally(ex -> {
                        world.execute(() -> {
                            if (previousGroup != null) {
                                plugin.getRuntimeManager().applyGroup(world, info, previousGroup, previousGroup.originalRotations != null ? previousGroup.originalRotations : new java.util.HashMap<>());
                            }
                            playerRef.sendMessage(Message.raw("Failed to apply image: " + ex.getMessage()));
                        });
                        return null;
                    });
        });
    }

    private void addEventBindings(UIEventBuilder eventBuilder) {
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ApplyButton",
                EventData.of("Action", "Apply").append("@Url", "#UrlInput.Value")
                        .append("@Fit", "#FitDropdown.Value")
                        .append("@Rotation", "#RotationInput.Value")
                        .append("@FlipX", "#FlipXContainer #CheckBox.Value")
                        .append("@FlipY", "#FlipYContainer #CheckBox.Value")
                        .append("@HideFrame", "#HideFrameContainer #CheckBox.Value"),
                false);
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#CancelButton",
                EventData.of("Action", "Cancel"), false);
    }

    private int parseInt(String text, int fallback) {
        if (text == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private String resolveFacing(Store<EntityStore> store,
            dev.jacobwasbeast.runtime.ImageFrameRuntimeManager.GroupInfo info) {
        Vector4d hitLocation = null;
        Vector3i target = blockPos;
        if (interactionContext != null) {
            hitLocation = interactionContext.getMetaStore().getIfPresentMetaObject(Interaction.HIT_LOCATION);
            if (target == null) {
                BlockPosition raw = interactionContext.getMetaStore()
                        .getIfPresentMetaObject(Interaction.TARGET_BLOCK_RAW);
                if (raw != null) {
                    target = new Vector3i(raw.x, raw.y, raw.z);
                }
            }
        }
        Vector3d playerPos = null;
        try {
            var ref = playerRef.getReference();
            if (ref != null && ref.isValid()) {
                var transform = store.getComponent(ref, TransformComponent.getComponentType());
                if (transform != null) {
                    playerPos = transform.getPosition();
                }
            }
        } catch (Exception ignored) {
        }
        return plugin.getRuntimeManager().resolveFacing(info, hitLocation, target, playerPos);
    }

    public static class FramePageData {
        public static final BuilderCodec<FramePageData> CODEC = BuilderCodec
                .builder(FramePageData.class, FramePageData::new)
                .append(new KeyedCodec<>("Action", Codec.STRING), (d, v) -> d.action = v, d -> d.action)
                .add()
                .append(new KeyedCodec<>("@Url", Codec.STRING), (d, v) -> d.url = v, d -> d.url)
                .add()
                .append(new KeyedCodec<>("@Fit", Codec.STRING), (d, v) -> d.fit = v, d -> d.fit)
                .add()
                .append(new KeyedCodec<>("@Rotation", Codec.STRING), (d, v) -> d.rotation = v, d -> d.rotation)
                .add()
                .append(new KeyedCodec<>("@FlipX", Codec.BOOLEAN), (d, v) -> d.flipX = v, d -> d.flipX)
                .add()
                .append(new KeyedCodec<>("@FlipY", Codec.BOOLEAN), (d, v) -> d.flipY = v, d -> d.flipY)
                .add()
                .append(new KeyedCodec<>("@HideFrame", Codec.BOOLEAN), (d, v) -> d.hideFrame = v, d -> d.hideFrame)
                .add()
                .build();

        public String action;
        public String url;
        public String fit;
        public String rotation;
        public boolean flipX;
        public boolean flipY;
        public boolean hideFrame;
    }
}
