package dev.jacobwasbeast.runtime;

import com.hypixel.hytale.common.plugin.PluginManifest;
import com.hypixel.hytale.common.semver.Semver;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.math.vector.Vector4d;
import com.hypixel.hytale.server.core.asset.AssetModule;
import com.hypixel.hytale.assetstore.AssetUpdateQuery;
import com.hypixel.hytale.server.core.asset.common.CommonAssetModule;
import com.hypixel.hytale.server.core.asset.common.CommonAssetRegistry;
import com.hypixel.hytale.server.core.asset.common.asset.FileCommonAsset;
import com.hypixel.hytale.protocol.Packet;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import dev.jacobwasbeast.ImageFramesPlugin;
import dev.jacobwasbeast.store.ImageFrameStore;
import dev.jacobwasbeast.store.ImageFrameStore.FrameGroup;
import it.unimi.dsi.fastutil.booleans.BooleanObjectPair;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import javax.imageio.ImageIO;

public class ImageFrameRuntimeManager {
    public static final String BASE_BLOCK_ID = "image_frames:frame";
    public static final String SLIM_BLOCK_ID = "image_frames:slim_frame";
    public static final String PANEL_BLOCK_ID = "image_frames:panel";
    public static final String PANEL_INVISIBLE_BLOCK_ID = "image_frames:panel_invisible";
    public static final String TILE_PREFIX = "ImageFrames_Tile_";
    private static final String AIR_BLOCK_ID = "empty";

    private static final String RUNTIME_ASSETS_PACK = "ImageFramesRuntimeAssets";
    private static final String RUNTIME_ASSETS_DIR = "image_frames_assets";
    private static final String RUNTIME_BLOCKS_DIR = "Server/Item/Block/Blocks/ImageFramesData";
    private static final String FRAME_TEXTURE_PATH = "Blocks/ImageFrames/Frame.png";
    private static final String PANEL_TEXTURE_PATH = "Blocks/ImageFrames/Panel.png";
    private static final String TILE_TEXTURE_DIR = "Blocks/ImageFrames/tiles/";
    private static final String PANEL_MODEL_DIR = "Blocks/ImageFrames/PanelModels/";
    private static final String PANEL_MODEL_PATH = "Blocks/ImageFrames/Panel.blockymodel";
    private static final AssetUpdateQuery TILE_UPDATE_QUERY = new AssetUpdateQuery(
            new AssetUpdateQuery.RebuildCache(true, false, false, false, false, false));

    private final ImageFramesPlugin plugin;
    private final ImageFrameStore store;
    private final Path runtimeAssetsPath;
    private final Path runtimeCommonBlocksPath;
    private final Path runtimeBlockTypesPath;
    private volatile BufferedImage frameTextureCache;
    private volatile BufferedImage panelTextureCache;
    private final ImageFrameImageCache imageCache;
    private final AtomicBoolean integrityCheckStarted = new AtomicBoolean(false);

    public ImageFrameRuntimeManager(ImageFramesPlugin plugin, ImageFrameStore store) {
        this.plugin = plugin;
        this.store = store;
        Path baseDir = resolveRuntimeBasePath();
        this.runtimeAssetsPath = baseDir.resolve(RUNTIME_ASSETS_DIR).toAbsolutePath();
        this.runtimeCommonBlocksPath = runtimeAssetsPath.resolve("Common/Blocks/ImageFrames/tiles");
        this.runtimeBlockTypesPath = runtimeAssetsPath.resolve(RUNTIME_BLOCKS_DIR);
        this.imageCache = new ImageFrameImageCache(java.nio.file.Path.of("ImageFrames"));
    }

    public void init() {
        try {
            Files.createDirectories(runtimeCommonBlocksPath);
            Files.createDirectories(runtimeBlockTypesPath);
            ensureRuntimePackImmutableMarker();
            registerRuntimeAssetsPack();
            loadExistingRuntimeAssets();
            cleanupRuntimeAssetsAgainstStore();
            rebuildAssetsFromStore();
        } catch (Exception e) {
            plugin.getLogger().at(Level.SEVERE).withCause(e).log("Failed to initialize ImageFrames runtime assets");
        }
    }

    public void startIntegrityChecks(long intervalSeconds) {
        if (intervalSeconds <= 0 || !integrityCheckStarted.compareAndSet(false, true)) {
            return;
        }
        com.hypixel.hytale.server.core.HytaleServer.SCHEDULED_EXECUTOR.scheduleWithFixedDelay(
                this::validateGroupsIntegrity,
                intervalSeconds,
                intervalSeconds,
                java.util.concurrent.TimeUnit.SECONDS);
    }

    private void validateGroupsIntegrity() {
        Map<String, FrameGroup> groups = store.getGroupsSnapshot();
        if (groups == null || groups.isEmpty()) {
            return;
        }
        for (FrameGroup group : groups.values()) {
            if (group == null || group.worldName == null) {
                continue;
            }
            World world = com.hypixel.hytale.server.core.universe.Universe.get().getWorld(group.worldName);
            if (world == null) {
                continue;
            }
            world.execute(() -> validateGroupInWorld(world, group));
        }
    }

    private void validateGroupInWorld(World world, FrameGroup group) {
        if (world == null || group == null) {
            return;
        }
        GroupInfo info = buildGroupInfoFromStore(group);
        if (info.blocks == null || info.blocks.isEmpty()) {
            return;
        }
        Vector3i missing = null;
        for (Vector3i pos : info.blocks) {
            if (pos == null) {
                continue;
            }
            long chunkIndex = com.hypixel.hytale.math.util.ChunkUtil.indexChunkFromBlock(pos.getX(), pos.getZ());
            if (world.getChunk(chunkIndex) == null) {
                // Avoid false positives when chunks are not loaded.
                return;
            }
            BlockType blockType = world.getBlockType(pos);
            String blockId = blockType != null ? blockType.getId() : null;
            if (!isFrameBlockId(blockId)) {
                missing = pos;
                break;
            }
        }
        if (missing != null) {
            dropGroupItems(world, group, missing);
            removeGroupAndAssets(world, group);
        }
    }

    private boolean isFrameBlockId(String blockId) {
        return BASE_BLOCK_ID.equals(blockId)
                || SLIM_BLOCK_ID.equals(blockId)
                || PANEL_BLOCK_ID.equals(blockId)
                || PANEL_INVISIBLE_BLOCK_ID.equals(blockId)
                || (blockId != null && blockId.startsWith(TILE_PREFIX));
    }

    private void dropGroupItems(World world, FrameGroup group, Vector3i pos) {
        if (world == null || group == null) {
            return;
        }
        int count = Math.max(0, group.sizeX * group.sizeY * group.sizeZ);
        if (count <= 0) {
            return;
        }
        String dropItemId = BASE_BLOCK_ID;
        if (group.blockId != null) {
            if (SLIM_BLOCK_ID.equals(group.blockId)) {
                dropItemId = SLIM_BLOCK_ID;
            } else if (PANEL_BLOCK_ID.equals(group.blockId) || PANEL_INVISIBLE_BLOCK_ID.equals(group.blockId)) {
                dropItemId = PANEL_BLOCK_ID;
            }
        }
        Vector3d dropPos;
        if (pos != null) {
            dropPos = new Vector3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        } else {
            dropPos = new Vector3d(group.minX + group.sizeX / 2.0, group.minY + group.sizeY / 2.0,
                    group.minZ + group.sizeZ / 2.0);
        }
        var store = world.getEntityStore().getStore();
        int remaining = count;
        while (remaining > 0) {
            int qty = Math.min(remaining, 64);
            ItemStack stack = new ItemStack(dropItemId, qty, null);
            var holder = ItemComponent.generateItemDrop(store, stack, dropPos, Vector3f.ZERO, 0.0F, 0.0F, 0.0F);
            if (holder != null) {
                store.addEntity(holder, AddReason.SPAWN);
            }
            remaining -= qty;
        }
    }

    private void registerRuntimeAssetsPack() {
        try {
            PluginManifest manifest = PluginManifest.CoreBuilder.corePlugin(ImageFramesPlugin.class)
                    .description("Runtime assets for ImageFrames")
                    .build();
            manifest.setName(RUNTIME_ASSETS_PACK);
            manifest.setVersion(Semver.fromString("1.0.0"));
            AssetModule.get().registerPack(RUNTIME_ASSETS_PACK, runtimeAssetsPath, manifest);
        } catch (Exception e) {
            plugin.getLogger().at(Level.SEVERE).withCause(e).log("Failed to register ImageFrames runtime pack");
        }
    }

    private void loadExistingRuntimeAssets() {
        ensureCommonAssetsRegistered();
        ensureBlockTypesRegistered();
    }

    private void cleanupRuntimeAssetsAgainstStore() {
        Map<String, FrameGroup> groups = store.getGroupsSnapshot();
        Set<String> expectedBaseNames = new HashSet<>();
        if (groups != null) {
            for (FrameGroup group : groups.values()) {
                if (group == null) {
                    continue;
                }
                String safeId = (group.safeId != null && !group.safeId.isEmpty())
                        ? group.safeId
                        : sanitizeFilename(group.groupId);
                GroupInfo info = new GroupInfo(group.worldName, group.minX, group.minY, group.minZ,
                        group.sizeX, group.sizeY, group.sizeZ, java.util.Collections.emptyList(), null, group.blockId);
                for (int ty = 0; ty < info.height; ty++) {
                    for (int tx = 0; tx < info.width; tx++) {
                        expectedBaseNames.add(safeId + "_" + tx + "_" + ty);
                    }
                }
            }
        }

        List<Path> removeJsonPaths = new ArrayList<>();
        List<com.hypixel.hytale.server.core.asset.common.CommonAssetRegistry.PackAsset> removedCommon = new ArrayList<>();

        if (Files.isDirectory(runtimeCommonBlocksPath)) {
            try (var stream = Files.list(runtimeCommonBlocksPath)) {
                stream.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".png")).forEach(path -> {
                    String fileName = path.getFileName().toString();
                    String baseName = fileName.substring(0, fileName.length() - 4);
                    if (expectedBaseNames.contains(baseName)) {
                        return;
                    }
                    String assetPath = TILE_TEXTURE_DIR + fileName;
                    BooleanObjectPair<com.hypixel.hytale.server.core.asset.common.CommonAssetRegistry.PackAsset> removed = CommonAssetRegistry
                            .removeCommonAssetByName(RUNTIME_ASSETS_PACK, assetPath);
                    if (removed != null && removed.second() != null) {
                        removedCommon.add(removed.second());
                    }
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                    }
                    Path jsonPath = runtimeBlockTypesPath.resolve(TILE_PREFIX + baseName + ".json");
                    if (Files.exists(jsonPath)) {
                        removeJsonPaths.add(jsonPath);
                        try {
                            Files.deleteIfExists(jsonPath);
                        } catch (IOException ignored) {
                        }
                    }
                });
            } catch (IOException e) {
                plugin.getLogger().at(Level.WARNING).withCause(e).log("Failed to cleanup runtime ImageFrames tiles");
            }
        }

        if (!removeJsonPaths.isEmpty()) {
            try {
                @SuppressWarnings("unchecked")
                var assetStore = (com.hypixel.hytale.server.core.asset.HytaleAssetStore<String, BlockType, com.hypixel.hytale.assetstore.map.BlockTypeAssetMap<String, BlockType>>) BlockType
                        .getAssetStore();
                assetStore.removeAssetWithPaths(RUNTIME_ASSETS_PACK, removeJsonPaths, TILE_UPDATE_QUERY);
            } catch (Exception e) {
                plugin.getLogger().at(Level.WARNING).withCause(e)
                        .log("Failed to remove orphaned ImageFrames block types");
            }
        }

        if (!removedCommon.isEmpty()) {
            CommonAssetModule module = CommonAssetModule.get();
            if (module != null) {
                module.sendRemoveAssets(removedCommon, false);
            }
        }
    }

    private void rebuildAssetsFromStore() {
        Map<String, FrameGroup> groups = store.getGroupsSnapshot();
        if (groups == null || groups.isEmpty()) {
            return;
        }
        List<Path> blockTypePaths = new ArrayList<>();
        for (FrameGroup group : groups.values()) {
            if (group == null || group.url == null || group.url.isEmpty()) {
                continue;
            }
            try {
                GroupInfo info = new GroupInfo(group.worldName, group.minX, group.minY, group.minZ,
                        group.sizeX, group.sizeY, group.sizeZ, java.util.Collections.emptyList(), null, group.blockId);
                BufferedImage source = loadSourceImage(group.url);
                rebuildGroupAssetsFromSource(info, group, source, blockTypePaths);
                store.putGroup(group);
            } catch (Exception e) {
                plugin.getLogger().at(Level.WARNING).withCause(e).log("Failed to rebuild ImageFrame assets for %s",
                        group.groupId);
            }
        }
        loadBlockTypeAssets(blockTypePaths);
    }

    private void ensureCommonAssetsRegistered() {
        CommonAssetModule commonAssetModule = CommonAssetModule.get();
        if (commonAssetModule == null || !Files.isDirectory(runtimeCommonBlocksPath)) {
            return;
        }
        try (var stream = Files.list(runtimeCommonBlocksPath)) {
            stream.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".png")).forEach(path -> {
                String fileName = path.getFileName().toString();
                String assetPath = "Blocks/ImageFrames/tiles/" + fileName;
                if (CommonAssetRegistry.hasCommonAsset(assetPath)) {
                    return;
                }
                try {
                    byte[] bytes = Files.readAllBytes(path);
                    commonAssetModule.addCommonAsset(RUNTIME_ASSETS_PACK, new FileCommonAsset(path, assetPath, bytes));
                } catch (IOException e) {
                    plugin.getLogger().at(Level.WARNING).withCause(e).log("Failed to register tile asset %s",
                            assetPath);
                }
            });
        } catch (IOException e) {
            plugin.getLogger().at(Level.WARNING).withCause(e).log("Failed to scan tile assets");
        }
    }

    private void ensureRuntimePackImmutableMarker() {
        try {
            Files.createDirectories(runtimeAssetsPath);
            Path marker = runtimeAssetsPath.resolve("CommonAssetsIndex.hashes");
            if (!Files.exists(marker)) {
                Files.writeString(marker, "VERSION=0\n");
            }
        } catch (IOException e) {
            plugin.getLogger().at(Level.WARNING).withCause(e).log("Failed to write runtime asset index marker");
        }
    }

    private void ensureBlockTypesRegistered() {
        // This method is only called during init() - it ensures all existing block types are registered
        // For runtime operations, use the more efficient methods that only load missing assets
        if (!Files.isDirectory(runtimeCommonBlocksPath)) {
            return;
        }
        try {
            Files.createDirectories(runtimeBlockTypesPath);
        } catch (IOException e) {
            plugin.getLogger().at(Level.WARNING).withCause(e).log("Failed to create runtime block type directory");
            return;
        }

        // CRITICAL: Ensure textures are registered FIRST before creating/loading block types
        ensureCommonAssetsRegistered();

        try (var stream = Files.list(runtimeCommonBlocksPath)) {
            stream.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".png")).forEach(path -> {
                String fileName = path.getFileName().toString();
                String baseName = fileName.substring(0, fileName.length() - 4);
                String tileKey = TILE_PREFIX + baseName;
                String assetPath = TILE_TEXTURE_DIR + fileName;
                Path jsonPath = runtimeBlockTypesPath.resolve(tileKey + ".json");
                try {
                    // Verify texture is registered before creating block type JSON
                    if (!CommonAssetRegistry.hasCommonAsset(assetPath)) {
                        plugin.getLogger().at(Level.WARNING).log("Texture %s not registered, skipping block type %s", assetPath, tileKey);
                        return;
                    }
                    Axis normalAxis = parseNormalAxisFromTileName(baseName);
                    String facing = parseFacingFromTileName(baseName);
                    int defaultTileSize = 32;
                    writeStringIfChanged(jsonPath,
                            buildTileBlockTypeJson(assetPath, normalAxis, facing, BASE_BLOCK_ID, false, null, defaultTileSize, true, false));
                } catch (IOException e) {
                    plugin.getLogger().at(Level.WARNING).withCause(e).log("Failed to write tile block type %s",
                            tileKey);
                }
            });
        } catch (IOException e) {
            plugin.getLogger().at(Level.WARNING).withCause(e).log("Failed to scan runtime tiles for block types");
        }

        // Load only block types that aren't already loaded
        List<Path> jsonPaths = listJsonFiles(runtimeBlockTypesPath);
        List<Path> missingJsonPaths = new ArrayList<>();
        for (Path jsonPath : jsonPaths) {
            String fileName = jsonPath.getFileName().toString();
            String key = fileName.substring(0, fileName.length() - 5); // Remove .json
            if (BlockType.getAssetMap().getAsset(key) == null) {
                missingJsonPaths.add(jsonPath);
            }
        }
        if (!missingJsonPaths.isEmpty()) {
            loadBlockTypeAssets(missingJsonPaths);
        }
    }

    public GroupInfo collectGroupInfo(World world, Vector3i target, Axis preferredNormal) throws IOException {
        FrameGroup existing = store.getGroupByPos(world.getName(), target);
        GroupInfo info = existing != null ? buildGroupInfoFromStore(existing)
                : collectGroup(world, target, preferredNormal);
        if (!info.valid) {
            throw new IOException("Frames must be in a flat rectangle (e.g., 2x2, 3x3)");
        }
        return info;
    }

    public GroupInfo collectGroupInfo(World world, Vector3i target) throws IOException {
        return collectGroupInfo(world, target, null);
    }

    public FrameGroup buildGroupAssets(GroupInfo info, String url, String fit, int rot, boolean flipX, boolean flipY,
            String ownerUuid, String facing, String blockId, boolean hideFrame, boolean collision)
            throws IOException {
        String groupId = info.worldName + ":" + info.minX + ":" + info.minY + ":" + info.minZ + ":" + info.sizeX + "x"
                + info.sizeY + "x" + info.sizeZ;
        String fileGroupId = buildSafeGroupId(url, info, facing, rot);

        BufferedImage source = loadSourceImage(url);
        int tileSize = plugin.getConfig().getTileSize();
        // Apply fit modes directly to target dimensions (not square first)
        int targetW = info.width * tileSize;
        int targetH = info.height * tileSize;
        BufferedImage processed = scaleImage(source, targetW, targetH, fit);
        if (rot != 0) {
            processed = rotate(processed, rot);
        }
        processed = applyFlips(processed, flipX, flipY);
        // Ensure exact target size - fit modes should handle this, but pad/crop if needed
        if (processed.getWidth() != targetW || processed.getHeight() != targetH) {
            BufferedImage finalImage = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = finalImage.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            // Center the image
            int x = Math.max(0, (targetW - processed.getWidth()) / 2);
            int y = Math.max(0, (targetH - processed.getHeight()) / 2);
            g.drawImage(processed, x, y, null);
            g.dispose();
            processed = finalImage;
        }
        // For slim frames, don't apply frame underlay - the model handles it separately with multi-texture
        // For panels, build a texture atlas (frame on left, image on right)
        if (!SLIM_BLOCK_ID.equals(blockId)) {
            if (!PANEL_BLOCK_ID.equals(blockId) && !PANEL_INVISIBLE_BLOCK_ID.equals(blockId)) {
                // Regular frames: apply frame underlay based on hideFrame
                processed = applyFrameUnderlay(processed, hideFrame);
            }
        }

        List<Path> blockTypePaths = new ArrayList<>();
        FrameGroup group = new FrameGroup(groupId);
        group.safeId = fileGroupId;
        group.worldName = info.worldName;
        group.minX = info.minX;
        group.minY = info.minY;
        group.minZ = info.minZ;
        group.sizeX = info.sizeX;
        group.sizeY = info.sizeY;
        group.sizeZ = info.sizeZ;
        group.url = url;
        group.fit = fit;
        group.rot = rot;
        group.flipX = flipX;
        group.flipY = flipY;
        group.ownerUuid = ownerUuid;
        group.facing = facing;
        group.blockId = blockId;
        group.hideFrame = hideFrame;
        group.collision = collision;
        group.tileBlocks.clear();
        String panelModelPath = null;
        if (PANEL_BLOCK_ID.equals(group.blockId)) {
            plugin.getLogger().at(Level.INFO).log("Generating panel model for tileSize=%d, hideFrame=%b", tileSize, hideFrame);
            panelModelPath = ensurePanelModel(tileSize, hideFrame);
            plugin.getLogger().at(Level.INFO).log("Panel model path: %s", panelModelPath);
        }

        for (int ty = 0; ty < info.height; ty++) {
            for (int tx = 0; tx < info.width; tx++) {
                int px = tx * tileSize;
                int py = ty * tileSize;
                // Ensure we don't go out of bounds
                int availableW = Math.min(tileSize, processed.getWidth() - px);
                int availableH = Math.min(tileSize, processed.getHeight() - py);
                BufferedImage tile;
                if (availableW == tileSize && availableH == tileSize) {
                    tile = processed.getSubimage(px, py, tileSize, tileSize);
                } else {
                    // Pad to square if needed
                    tile = new BufferedImage(tileSize, tileSize, BufferedImage.TYPE_INT_ARGB);
                    Graphics2D gTile = tile.createGraphics();
                    gTile.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    gTile.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                    BufferedImage subTile = processed.getSubimage(px, py, availableW, availableH);
                    gTile.drawImage(subTile, 0, 0, null);
                    gTile.dispose();
                }
                // For panels, build texture atlas (frame on left, image on right)
                if (PANEL_BLOCK_ID.equals(blockId)) {
                    boolean includeFrame = !hideFrame;
                    plugin.getLogger().at(Level.FINE).log("Building panel atlas for tile %d,%d with includeFrame=%b (hideFrame=%b)", tx, ty, includeFrame, hideFrame);
                    tile = buildPanelAtlas(tile, includeFrame);
                }

                String tileBaseName = fileGroupId + "_" + tx + "_" + ty;
                String tileKey = TILE_PREFIX + tileBaseName;
                String assetPath = TILE_TEXTURE_DIR + tileBaseName + ".png";
                Path filePath = runtimeCommonBlocksPath.resolve(tileBaseName + ".png");
                byte[] pngBytes = encodePng(tile);
                boolean pngChanged = writeBytesIfChanged(filePath, pngBytes);
                boolean hasAsset = CommonAssetRegistry.hasCommonAsset(assetPath);
                if (pngChanged || !hasAsset) {
                    registerCommonAsset(assetPath, filePath, pngBytes);
                }
                Path jsonPath = runtimeBlockTypesPath.resolve(tileKey + ".json");
                // Bottom-left tile is at tx=0, ty=0
                boolean isBottomLeft = (tx == 0 && ty == 0);
                boolean jsonChanged = writeStringIfChanged(jsonPath,
                        buildTileBlockTypeJson(assetPath, info.normalAxis, facing, group.blockId, group.hideFrame,
                                panelModelPath, tileSize, group.collision, isBottomLeft));
                if (jsonChanged || BlockType.getAssetMap().getAsset(tileKey) == null) {
                    blockTypePaths.add(jsonPath);
                }

                Vector3i pos = info.toWorldPos(tx, ty, facing);
                group.tileBlocks.put(
                        ImageFrameStore.toPosKey(info.worldName, pos.getX(), pos.getY(), pos.getZ()),
                        tileKey);
            }
        }
        loadBlockTypeAssets(blockTypePaths);
        return group;
    }

    private void rebuildGroupAssetsFromSource(GroupInfo info, FrameGroup group, BufferedImage source,
            List<Path> blockTypePaths)
            throws IOException {
        if (source == null) {
            throw new IOException("Missing image source");
        }
        String safeId = (group.safeId != null && !group.safeId.isEmpty())
                ? group.safeId
                : sanitizeFilename(group.groupId);
        group.safeId = safeId;
        String facing = group.facing != null ? group.facing : "North";
        String fit = group.fit != null ? group.fit : "stretch";
        int tileSize = plugin.getConfig().getTileSize();
        // Apply fit modes directly to target dimensions (not square first)
        int targetW = info.width * tileSize;
        int targetH = info.height * tileSize;
        BufferedImage processed = scaleImage(source, targetW, targetH, fit);
        if (group.rot != 0) {
            processed = rotate(processed, group.rot);
        }
        processed = applyFlips(processed, group.flipX, group.flipY);
        // Ensure exact target size - fit modes should handle this, but pad/crop if needed
        if (processed.getWidth() != targetW || processed.getHeight() != targetH) {
            BufferedImage finalImage = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = finalImage.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            // Center the image
            int x = Math.max(0, (targetW - processed.getWidth()) / 2);
            int y = Math.max(0, (targetH - processed.getHeight()) / 2);
            g.drawImage(processed, x, y, null);
            g.dispose();
            processed = finalImage;
        }
        // For slim frames, don't apply frame underlay - the model handles it separately with multi-texture
        // For panels, build a texture atlas (frame on left, image on right)
        if (!SLIM_BLOCK_ID.equals(group.blockId)) {
            if (!PANEL_BLOCK_ID.equals(group.blockId) && !PANEL_INVISIBLE_BLOCK_ID.equals(group.blockId)) {
                // Regular frames: apply frame underlay based on hideFrame
                processed = applyFrameUnderlay(processed, group.hideFrame);
            }
        }

        group.tileBlocks.clear();
        String panelModelPath = null;
        if (PANEL_BLOCK_ID.equals(group.blockId)) {
            panelModelPath = ensurePanelModel(tileSize, group.hideFrame);
        }
        for (int ty = 0; ty < info.height; ty++) {
            for (int tx = 0; tx < info.width; tx++) {
                int px = tx * tileSize;
                int py = ty * tileSize;
                // Ensure we don't go out of bounds
                int availableW = Math.min(tileSize, processed.getWidth() - px);
                int availableH = Math.min(tileSize, processed.getHeight() - py);
                BufferedImage tile;
                if (availableW == tileSize && availableH == tileSize) {
                    tile = processed.getSubimage(px, py, tileSize, tileSize);
                } else {
                    // Pad to square if needed
                    tile = new BufferedImage(tileSize, tileSize, BufferedImage.TYPE_INT_ARGB);
                    Graphics2D gTile = tile.createGraphics();
                    gTile.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    gTile.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                    BufferedImage subTile = processed.getSubimage(px, py, availableW, availableH);
                    gTile.drawImage(subTile, 0, 0, null);
                    gTile.dispose();
                }
                // For panels, build texture atlas (frame on left, image on right)
                if (PANEL_BLOCK_ID.equals(group.blockId)) {
                    boolean includeFrame = !group.hideFrame;
                    tile = buildPanelAtlas(tile, includeFrame);
                }

                String tileBaseName = safeId + "_" + tx + "_" + ty;
                String tileKey = TILE_PREFIX + tileBaseName;
                String assetPath = TILE_TEXTURE_DIR + tileBaseName + ".png";
                Path filePath = runtimeCommonBlocksPath.resolve(tileBaseName + ".png");
                byte[] pngBytes = encodePng(tile);
                boolean pngChanged = writeBytesIfChanged(filePath, pngBytes);
                boolean hasAsset = CommonAssetRegistry.hasCommonAsset(assetPath);
                if (pngChanged || !hasAsset) {
                    registerCommonAsset(assetPath, filePath, pngBytes);
                }
                Path jsonPath = runtimeBlockTypesPath.resolve(tileKey + ".json");
                // Bottom-left tile is at tx=0, ty=0
                boolean isBottomLeft = (tx == 0 && ty == 0);
                boolean jsonChanged = writeStringIfChanged(jsonPath,
                        buildTileBlockTypeJson(assetPath, info.normalAxis, facing, group.blockId, group.hideFrame,
                                panelModelPath, tileSize, group.collision, isBottomLeft));
                if (jsonChanged || BlockType.getAssetMap().getAsset(tileKey) == null) {
                    blockTypePaths.add(jsonPath);
                }
                Vector3i pos = info.toWorldPos(tx, ty, facing);
                group.tileBlocks.put(ImageFrameStore.toPosKey(info.worldName, pos.getX(), pos.getY(), pos.getZ()),
                        tileKey);
            }
        }
    }

    private BufferedImage loadSourceImage(String url) throws IOException {
        try {
            return imageCache.loadOrDownload(url, () -> {
                try {
                    return downloadImage(url);
                } catch (IOException e) {
                    return null;
                }
            });
        } catch (IOException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IOException("Failed to load image", e);
        }
    }

    public void applyGroup(World world, GroupInfo info, FrameGroup group, Map<Vector3i, Integer> rotations) {
        placeTiles(world, info, group, rotations);
        store.putGroup(group);
    }

    public void removeGroupAndAssets(World world, FrameGroup group) {
        if (group == null) {
            return;
        }
        store.removeGroup(group.groupId);
        if (world != null && group.tileBlocks != null && !group.tileBlocks.isEmpty()) {
            List<Vector3i> positions = new ArrayList<>();
            for (String posKey : group.tileBlocks.keySet()) {
                Vector3i pos = parsePosKey(group.worldName, posKey);
                if (pos != null) {
                    positions.add(pos);
                }
            }
            scheduleBlockClear(world, positions, 256, null);
            scheduleRemoveGroupAssets(group,5);
        }
    }

    private void scheduleBlockClear(World world, List<Vector3i> positions, int batchSize, Runnable onComplete) {
        if (positions == null || positions.isEmpty()) {
            if (onComplete != null) {
                onComplete.run();
            }
            return;
        }
        java.util.concurrent.atomic.AtomicInteger index = new java.util.concurrent.atomic.AtomicInteger(0);
        java.lang.Runnable task = new java.lang.Runnable() {
            @Override
            public void run() {
                world.execute(() -> {
                    int start = index.get();
                    int end = Math.min(start + batchSize, positions.size());
                    for (int i = start; i < end; i++) {
                        Vector3i pos = positions.get(i);
                        world.setBlock(pos.getX(), pos.getY(), pos.getZ(), AIR_BLOCK_ID);
                    }
                    if (end >= positions.size()) {
                        if (onComplete != null) {
                            onComplete.run();
                        }
                    } else {
                        index.set(end);
                        com.hypixel.hytale.server.core.HytaleServer.SCHEDULED_EXECUTOR
                                .schedule(this, 1, java.util.concurrent.TimeUnit.MILLISECONDS);
                    }
                });
            }
        };
        com.hypixel.hytale.server.core.HytaleServer.SCHEDULED_EXECUTOR
                .schedule(task, 1, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private void scheduleRemoveGroupAssets(FrameGroup group, int delaySeconds) {
        com.hypixel.hytale.server.core.HytaleServer.SCHEDULED_EXECUTOR.schedule(
                () -> removeGroupAssets(group),
                delaySeconds,
                java.util.concurrent.TimeUnit.SECONDS);
    }

    private void removeGroupAssets(FrameGroup group) {
        if (group == null) {
            return;
        }
        Map<String, FrameGroup> groupsSnapshot = store.getGroupsSnapshot();
        Set<String> expectedBaseNames = new HashSet<>();
        if (groupsSnapshot != null) {
            for (FrameGroup g : groupsSnapshot.values()) {
                if (g == null) {
                    continue;
                }
                String expectedSafeId = (g.safeId != null && !g.safeId.isEmpty())
                        ? g.safeId
                        : sanitizeFilename(g.groupId);
                GroupInfo gi = new GroupInfo(g.worldName, g.minX, g.minY, g.minZ,
                        g.sizeX, g.sizeY, g.sizeZ, java.util.Collections.emptyList(), null, g.blockId);
                for (int ty = 0; ty < gi.height; ty++) {
                    for (int tx = 0; tx < gi.width; tx++) {
                        expectedBaseNames.add(expectedSafeId + "_" + tx + "_" + ty);
                    }
                }
            }
        }
        String safeId = (group.safeId != null && !group.safeId.isEmpty())
                ? group.safeId
                : sanitizeFilename(group.groupId);
        GroupInfo info = new GroupInfo(group.worldName, group.minX, group.minY, group.minZ,
                group.sizeX, group.sizeY, group.sizeZ, java.util.Collections.emptyList(), null, group.blockId);
        List<Path> jsonPaths = new ArrayList<>();
        List<com.hypixel.hytale.server.core.asset.common.CommonAssetRegistry.PackAsset> removedCommon = new ArrayList<>();
        for (int ty = 0; ty < info.height; ty++) {
            for (int tx = 0; tx < info.width; tx++) {
                String baseName = safeId + "_" + tx + "_" + ty;
                if (expectedBaseNames.contains(baseName)) {
                    continue;
                }
                String assetPath = TILE_TEXTURE_DIR + baseName + ".png";
                Path pngPath = runtimeCommonBlocksPath.resolve(baseName + ".png");
                Path jsonPath = runtimeBlockTypesPath.resolve(TILE_PREFIX + baseName + ".json");

                BooleanObjectPair<com.hypixel.hytale.server.core.asset.common.CommonAssetRegistry.PackAsset> removed = CommonAssetRegistry
                        .removeCommonAssetByName(RUNTIME_ASSETS_PACK, assetPath);
                if (removed != null && removed.second() != null) {
                    removedCommon.add(removed.second());
                }
                try {
                    Files.deleteIfExists(pngPath);
                } catch (IOException ignored) {
                }
                if (Files.exists(jsonPath)) {
                    jsonPaths.add(jsonPath);
                }
                try {
                    Files.deleteIfExists(jsonPath);
                } catch (IOException ignored) {
                }
            }
        }
        if (!jsonPaths.isEmpty()) {
            try {
                @SuppressWarnings("unchecked")
                var assetStore = (com.hypixel.hytale.server.core.asset.HytaleAssetStore<String, BlockType, com.hypixel.hytale.assetstore.map.BlockTypeAssetMap<String, BlockType>>) BlockType
                        .getAssetStore();
                assetStore.removeAssetWithPaths(RUNTIME_ASSETS_PACK, jsonPaths, TILE_UPDATE_QUERY);
            } catch (Exception e) {
                plugin.getLogger().at(Level.WARNING).withCause(e).log("Failed to remove ImageFrames block types");
            }
        }
        if (!removedCommon.isEmpty()) {
            CommonAssetModule module = CommonAssetModule.get();
            if (module != null) {
                module.sendRemoveAssets(removedCommon, false);
            }
        }
    }

    private int readRotation(World world, Vector3i pos) {
        try {
            long chunkIndex = com.hypixel.hytale.math.util.ChunkUtil.indexChunkFromBlock(pos.getX(), pos.getZ());
            var chunk = world.getChunk(chunkIndex);
            if (chunk != null) {
                // Try all methods on chunk
                java.lang.reflect.Method[] methods = chunk.getClass().getMethods();
                for (java.lang.reflect.Method method : methods) {
                    if (method.getName().toLowerCase().contains("rotation") || method.getName().toLowerCase().contains("variant")) {
                        try {
                            if (method.getParameterCount() == 3) {
                                Object result = method.invoke(chunk, pos.getX(), pos.getY(), pos.getZ());
                                if (result instanceof Integer) {
                                    int rotation = (Integer) result;
                                    plugin.getLogger().at(Level.INFO).log("Read rotation %d using method %s at %d,%d,%d", rotation, method.getName(), pos.getX(), pos.getY(), pos.getZ());
                                    return rotation;
                                }
                            }
                        } catch (Exception ignored) {
                        }
                    }
                }

                // Try getBlockData and inspect all fields/methods
                try {
                    java.lang.reflect.Method getBlockData = chunk.getClass().getMethod("getBlockData", int.class, int.class, int.class);
                    Object blockData = getBlockData.invoke(chunk, pos.getX(), pos.getY(), pos.getZ());
                    if (blockData != null) {
                        // Try all fields
                        java.lang.reflect.Field[] fields = blockData.getClass().getFields();
                        for (java.lang.reflect.Field field : fields) {
                            String fieldName = field.getName().toLowerCase();
                            if (fieldName.contains("rotation") || fieldName.contains("variant")) {
                                try {
                                    Object value = field.get(blockData);
                                    if (value instanceof Integer) {
                                        int rotation = (Integer) value;
                                        plugin.getLogger().at(Level.INFO).log("Read rotation %d from field %s at %d,%d,%d", rotation, field.getName(), pos.getX(), pos.getY(), pos.getZ());
                                        return rotation;
                                    }
                                } catch (Exception ignored) {
                                }
                            }
                        }

                        // Try all methods on blockData
                        java.lang.reflect.Method[] blockDataMethods = blockData.getClass().getMethods();
                        for (java.lang.reflect.Method method : blockDataMethods) {
                            String methodName = method.getName().toLowerCase();
                            if ((methodName.contains("rotation") || methodName.contains("variant")) && method.getParameterCount() == 0) {
                                try {
                                    Object result = method.invoke(blockData);
                                    if (result instanceof Integer) {
                                        int rotation = (Integer) result;
                                        plugin.getLogger().at(Level.INFO).log("Read rotation %d using blockData method %s at %d,%d,%d", rotation, method.getName(), pos.getX(), pos.getY(), pos.getZ());
                                        return rotation;
                                    }
                                } catch (Exception ignored) {
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    plugin.getLogger().at(Level.FINE).log("getBlockData failed: %s", e.getMessage());
                }

                // Try getBlockType and inspect it
                try {
                    java.lang.reflect.Method getBlockType = chunk.getClass().getMethod("getBlockType", int.class, int.class, int.class);
                    Object blockType = getBlockType.invoke(chunk, pos.getX(), pos.getY(), pos.getZ());
                    if (blockType != null) {
                        java.lang.reflect.Method[] blockTypeMethods = blockType.getClass().getMethods();
                        for (java.lang.reflect.Method method : blockTypeMethods) {
                            String methodName = method.getName().toLowerCase();
                            if ((methodName.contains("rotation") || methodName.contains("variant")) && method.getParameterCount() == 0) {
                                try {
                                    Object result = method.invoke(blockType);
                                    if (result instanceof Integer) {
                                        int rotation = (Integer) result;
                                        plugin.getLogger().at(Level.INFO).log("Read rotation %d using blockType method %s at %d,%d,%d", rotation, method.getName(), pos.getX(), pos.getY(), pos.getZ());
                                        return rotation;
                                    }
                                } catch (Exception ignored) {
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            plugin.getLogger().at(Level.WARNING).withCause(e).log("Exception reading rotation at %d,%d,%d", pos.getX(), pos.getY(), pos.getZ());
        }
        plugin.getLogger().at(Level.WARNING).log("Failed to read rotation at %d,%d,%d", pos.getX(), pos.getY(), pos.getZ());
        return -1; // -1 means couldn't read rotation
    }

    public Map<Vector3i, Integer> readOriginalRotations(World world, GroupInfo info) {
        Map<Vector3i, Integer> rotations = new HashMap<>();
        String blockId = (info.blockId != null && !info.blockId.isEmpty()) ? info.blockId : BASE_BLOCK_ID;
        boolean isPanel = PANEL_BLOCK_ID.equals(blockId) || PANEL_INVISIBLE_BLOCK_ID.equals(blockId);
        if (!isPanel) {
            return rotations;
        }

        // Read rotation from ORIGINAL blocks BEFORE they get replaced
        plugin.getLogger().at(Level.INFO).log("Reading rotations from %d original panel blocks", info.blocks.size());
        for (Vector3i pos : info.blocks) {
            int rotation = readRotation(world, pos);
            if (rotation >= 0) {
                rotations.put(pos, rotation);
                plugin.getLogger().at(Level.INFO).log("Stored rotation %d for position %d,%d,%d", rotation, pos.getX(), pos.getY(), pos.getZ());
            } else {
                plugin.getLogger().at(Level.WARNING).log("Could not read rotation for position %d,%d,%d", pos.getX(), pos.getY(), pos.getZ());
            }
        }
        plugin.getLogger().at(Level.INFO).log("Read %d rotations out of %d blocks", rotations.size(), info.blocks.size());
        return rotations;
    }

    public void placePlaceholder(World world, GroupInfo info, Map<Vector3i, Integer> rotations) {
        if (world == null || info == null || info.blocks == null) {
            return;
        }
        String blockId = (info.blockId != null && !info.blockId.isEmpty()) ? info.blockId : BASE_BLOCK_ID;
        boolean isPanel = PANEL_BLOCK_ID.equals(blockId) || PANEL_INVISIBLE_BLOCK_ID.equals(blockId);

        for (Vector3i pos : info.blocks) {
            if (isPanel && rotations != null && rotations.containsKey(pos)) {
                // Use stored rotation from original block
                int rotation = rotations.get(pos);
                try {
                    long chunkIndex = com.hypixel.hytale.math.util.ChunkUtil.indexChunkFromBlock(pos.getX(), pos.getZ());
                    var chunk = world.getChunk(chunkIndex);
                    if (chunk != null) {
                        int newBlockId = BlockType.getAssetMap().getIndex(blockId);
                        BlockType blockType = BlockType.getAssetMap().getAsset(newBlockId);
                        if (blockType != null) {
                            chunk.setBlock(pos.getX(), pos.getY(), pos.getZ(), newBlockId, blockType, rotation, 0, 0);
                            continue;
                        }
                    }
                } catch (Exception ignored) {
                }
            }
            // Fallback to world.setBlock
            world.setBlock(pos.getX(), pos.getY(), pos.getZ(), blockId);
        }
    }

    public void applyGroupWhenReady(World world, GroupInfo info, FrameGroup group, int remaining,
            Runnable onSuccess, Runnable onTimeout) {
        // FIRST: Broadcast assets to ensure they're available before placing blocks
        broadcastGroupAssets(group);

        // Then check if ready and place immediately (no delay)
        world.execute(() -> {
            if (areTileBlockTypesReady(info, group)) {
                // Assets are broadcast and ready - place tiles immediately
                // Use stored rotations from group (read before placePlaceholder)
                Map<Vector3i, Integer> rotations = group.originalRotations != null ? group.originalRotations : new HashMap<>();
                placeTiles(world, info, group, rotations);
                store.putGroup(group);
                if (onSuccess != null) {
                    onSuccess.run();
                }
                return;
            }

            // Not ready yet - retry with delay if we have remaining attempts
            if (remaining <= 0) {
                if (onTimeout != null) {
                    onTimeout.run();
                }
                return;
            }

            // Retry after a short delay
            com.hypixel.hytale.server.core.HytaleServer.SCHEDULED_EXECUTOR
                    .schedule(() -> applyGroupWhenReady(world, info, group, remaining - 1, onSuccess, onTimeout),
                            250, java.util.concurrent.TimeUnit.MILLISECONDS);
        });
    }

    public boolean areTileBlockTypesReady(GroupInfo info, FrameGroup group) {
        String safeId = group.safeId != null && !group.safeId.isEmpty() ? group.safeId
                : sanitizeFilename(group.groupId);
        for (int ty = 0; ty < info.height; ty++) {
            for (int tx = 0; tx < info.width; tx++) {
                String key = TILE_PREFIX + safeId + "_" + tx + "_" + ty;
                if (BlockType.getAssetMap().getAsset(key) == null) {
                    return false;
                }
                String assetPath = "Blocks/ImageFrames/tiles/" + safeId + "_" + tx + "_" + ty + ".png";
                if (!CommonAssetRegistry.hasCommonAsset(assetPath)) {
                    return false;
                }
            }
        }
        return true;
    }

    public void broadcastRuntimeAssets() {
        // Collect all unique block type keys from all groups
        java.util.Set<String> allKeys = new java.util.HashSet<>();
        java.util.Set<String> allTexturePaths = new java.util.HashSet<>();
        for (FrameGroup group : store.getGroupsSnapshot().values()) {
            if (group == null || group.tileBlocks == null) {
                continue;
            }
            allKeys.addAll(group.tileBlocks.values());
            // Collect texture paths from block keys
            String safeId = group.safeId != null && !group.safeId.isEmpty() ? group.safeId : sanitizeFilename(group.groupId);
            GroupInfo info = new GroupInfo(group.worldName, group.minX, group.minY, group.minZ,
                    group.sizeX, group.sizeY, group.sizeZ, java.util.Collections.emptyList(), null, group.blockId);
            for (int ty = 0; ty < info.height; ty++) {
                for (int tx = 0; tx < info.width; tx++) {
                    allTexturePaths.add(TILE_TEXTURE_DIR + safeId + "_" + tx + "_" + ty + ".png");
                }
            }
        }

        if (allKeys.isEmpty()) {
            plugin.getLogger().at(java.util.logging.Level.INFO).log("No ImageFrames block types to broadcast");
            return;
        }

        // Step 1: Ensure textures are registered (only register missing ones)
        ensureCommonAssetsRegistered();

        // Step 2: Load only missing block types (don't reload everything)
        java.util.List<Path> missingBlockTypePaths = new java.util.ArrayList<>();
        for (String key : allKeys) {
            if (BlockType.getAssetMap().getAsset(key) == null) {
                Path jsonPath = runtimeBlockTypesPath.resolve(key + ".json");
                if (Files.exists(jsonPath)) {
                    missingBlockTypePaths.add(jsonPath);
                }
            }
        }
        if (!missingBlockTypePaths.isEmpty()) {
            plugin.getLogger().at(java.util.logging.Level.INFO).log("Loading %d missing block types", missingBlockTypePaths.size());
            loadBlockTypeAssets(missingBlockTypePaths);
        }

        // Step 3: Broadcast textures FIRST
        broadcastCommonAssets();

        // Step 4: Collect and broadcast block types
        java.util.Map<String, BlockType> loaded = new java.util.HashMap<>();
        for (String key : allKeys) {
            BlockType bt = BlockType.getAssetMap().getAsset(key);
            if (bt != null) {
                loaded.put(key, bt);
            }
        }

        if (!loaded.isEmpty()) {
            @SuppressWarnings("unchecked")
            var assetStore = (com.hypixel.hytale.server.core.asset.HytaleAssetStore<String, BlockType, com.hypixel.hytale.assetstore.map.BlockTypeAssetMap<String, BlockType>>) BlockType
                    .getAssetStore();
            Packet packet = assetStore.getPacketGenerator().generateUpdatePacket(assetStore.getAssetMap(), loaded,
                    TILE_UPDATE_QUERY);
            com.hypixel.hytale.server.core.universe.Universe.get().broadcastPacketNoCache(packet);
            plugin.getLogger().at(java.util.logging.Level.INFO).log("Broadcasted %d ImageFrames block types",
                    loaded.size());
        }
    }

    public void broadcastGroupAssets(FrameGroup group) {
        if (group == null || group.tileBlocks == null || group.tileBlocks.isEmpty()) {
            return;
        }

        // Step 1: Ensure textures are registered (only register missing ones)
        ensureCommonAssetsRegistered();

        // Step 2: Load only missing block types (don't reload everything)
        java.util.List<Path> missingPaths = new java.util.ArrayList<>();
        for (String key : group.tileBlocks.values()) {
            if (BlockType.getAssetMap().getAsset(key) == null) {
                Path jsonPath = runtimeBlockTypesPath.resolve(key + ".json");
                if (Files.exists(jsonPath)) {
                    missingPaths.add(jsonPath);
                }
            }
        }
        if (!missingPaths.isEmpty()) {
            loadBlockTypeAssets(missingPaths);
        }

        // Step 3: Broadcast textures FIRST
        broadcastCommonAssets();

        // Step 4: Collect and broadcast block types
        java.util.Map<String, BlockType> loaded = new java.util.HashMap<>();
        for (String key : group.tileBlocks.values()) {
            BlockType bt = BlockType.getAssetMap().getAsset(key);
            if (bt != null) {
                loaded.put(key, bt);
            }
        }
        if (!loaded.isEmpty()) {
            @SuppressWarnings("unchecked")
            var assetStore = (com.hypixel.hytale.server.core.asset.HytaleAssetStore<String, BlockType, com.hypixel.hytale.assetstore.map.BlockTypeAssetMap<String, BlockType>>) BlockType
                    .getAssetStore();
            Packet packet = assetStore.getPacketGenerator().generateUpdatePacket(assetStore.getAssetMap(), loaded,
                    TILE_UPDATE_QUERY);
            com.hypixel.hytale.server.core.universe.Universe.get().broadcastPacketNoCache(packet);
            plugin.getLogger().at(java.util.logging.Level.INFO).log("Broadcasted ImageFrames group block types: %d",
                    loaded.size());
        }
    }

    public void broadcastCommonAssets() {
        ensureCommonAssetsRegistered();
        CommonAssetModule commonAssetModule = CommonAssetModule.get();
        if (commonAssetModule == null) {
            return;
        }
        java.util.List<com.hypixel.hytale.server.core.asset.common.CommonAsset> assets = CommonAssetRegistry
                .getCommonAssetsStartingWith(RUNTIME_ASSETS_PACK, "Blocks/ImageFrames/tiles/");
        if (assets == null || assets.isEmpty()) {
            return;
        }
        commonAssetModule.sendAssets(assets, false);
        plugin.getLogger().at(java.util.logging.Level.INFO).log("Broadcasted %d ImageFrames textures", assets.size());
    }

    public void refreshFramesForWorld(com.hypixel.hytale.server.core.universe.world.World world) {
        if (world == null)
            return;
        String worldName = world.getName();

        // Collect all block type keys that need to be loaded for this world
        java.util.Set<String> requiredKeys = new java.util.HashSet<>();
        java.util.List<dev.jacobwasbeast.store.ImageFrameStore.FrameGroup> worldGroups = new java.util.ArrayList<>();
        for (dev.jacobwasbeast.store.ImageFrameStore.FrameGroup group : store.getGroupsSnapshot().values()) {
            if (group == null || group.tileBlocks == null || !worldName.equals(group.worldName)) {
                continue;
            }
            worldGroups.add(group);
            requiredKeys.addAll(group.tileBlocks.values());
        }

        if (worldGroups.isEmpty()) {
            return;
        }

        // Load only missing block types (don't reload everything)
        java.util.List<Path> missingPaths = new java.util.ArrayList<>();
        for (String key : requiredKeys) {
            if (BlockType.getAssetMap().getAsset(key) == null) {
                Path jsonPath = runtimeBlockTypesPath.resolve(key + ".json");
                if (Files.exists(jsonPath)) {
                    missingPaths.add(jsonPath);
                }
            }
        }
        if (!missingPaths.isEmpty()) {
            loadBlockTypeAssets(missingPaths);
        }

        // Place tiles for each group
        for (dev.jacobwasbeast.store.ImageFrameStore.FrameGroup group : worldGroups) {
            // Build GroupInfo from stored group
            GroupInfo info = buildGroupInfoFromStore(group);
            if (!info.valid) {
                continue;
            }

            // Verify all block types for this group are loaded before placing
            boolean allLoaded = true;
            for (String key : group.tileBlocks.values()) {
                if (BlockType.getAssetMap().getAsset(key) == null) {
                    allLoaded = false;
                    break;
                }
            }
            if (!allLoaded) {
                continue;
            }

            // Read rotations from current blocks before placing tiles
            Map<Vector3i, Integer> rotations = new HashMap<>();
            boolean isPanel = PANEL_BLOCK_ID.equals(group.blockId) || PANEL_INVISIBLE_BLOCK_ID.equals(group.blockId);
            if (isPanel && info.blocks != null) {
                for (Vector3i pos : info.blocks) {
                    int rotation = readRotation(world, pos);
                    if (rotation >= 0) {
                        rotations.put(pos, rotation);
                    }
                }
            }

            // Use placeTiles to preserve rotations
            placeTiles(world, info, group, rotations);
        }
    }

    private FileCommonAsset registerCommonAsset(String assetPath, Path filePath, byte[] bytes) {
        CommonAssetModule commonAssetModule = CommonAssetModule.get();
        if (commonAssetModule == null) {
            return null;
        }
        FileCommonAsset asset = new FileCommonAsset(filePath, assetPath, bytes);
        commonAssetModule.addCommonAsset(RUNTIME_ASSETS_PACK, asset);
        return asset;
    }

    private void loadBlockTypeAssets(List<Path> paths) {
        if (paths == null || paths.isEmpty()) {
            return;
        }
        try {
            @SuppressWarnings("unchecked")
            var assetStore = (com.hypixel.hytale.server.core.asset.HytaleAssetStore<String, BlockType, com.hypixel.hytale.assetstore.map.BlockTypeAssetMap<String, BlockType>>) BlockType
                    .getAssetStore();
            assetStore.loadAssetsFromPaths(RUNTIME_ASSETS_PACK, paths, TILE_UPDATE_QUERY, true);
        } catch (Exception e) {
            plugin.getLogger().at(Level.WARNING).withCause(e).log("Failed to load ImageFrames block types");
        }
    }

    private List<Path> listJsonFiles(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return java.util.Collections.emptyList();
        }
        try (var stream = Files.list(dir)) {
            return stream.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".json"))
                    .collect(java.util.stream.Collectors.toList());
        } catch (IOException e) {
            plugin.getLogger().at(Level.WARNING).withCause(e).log("Failed to scan block type jsons");
            return java.util.Collections.emptyList();
        }
    }

    private String buildTileBlockTypeJson(String texturePath, Axis normalAxis, String facing, String blockId,
            boolean hideFrame, String panelModelPath, int tileSize, boolean collision, boolean isBottomLeft) {
        if (PANEL_BLOCK_ID.equals(blockId)) {
            // For panels, use a single atlas texture with UV offsets (frame on left, image on right)
            // Use the panel model (generated with hideFrame parameter)
            String model = panelModelPath;
            if (model == null) {
                plugin.getLogger().at(Level.WARNING).log("No model path provided for panel with hideFrame=%b, tileSize=%d", hideFrame, tileSize);
                if (hideFrame) {
                    // Can't use static model - it has a frame!
                    plugin.getLogger().at(Level.SEVERE).log("Cannot create invisible panel without dynamic model!");
                    model = PANEL_MODEL_PATH; // Will show frame, but prevents crash
                } else {
                    model = PANEL_MODEL_PATH;
                }
            }
            plugin.getLogger().at(Level.FINE).log("Using model %s for panel block with hideFrame=%b", model, hideFrame);

            // Calculate scale factor: model is tileSize x tileSize, but should render at 32x32 world units
            // Scale = 32 / tileSize (e.g., 32/512 = 0.0625 for 512px tiles)
            double scaleFactor = 32.0 / tileSize;

            // Match static panel JSON exactly, minus Flags/Interactions, plus CustomModelTexture
            StringBuilder json = new StringBuilder();
            json.append("{\n");
            if (collision) {
                // Collision enabled: all tiles use Solid material
                json.append("  \"Material\": \"Solid\",\n");
            } else if (isBottomLeft) {
                // Collision disabled AND bottom-left tile: Material Empty with Support
                json.append("  \"Material\": \"Empty\",\n");
            } else {
                // Collision disabled AND NOT bottom-left: Material Solid with thin hitbox
                json.append("  \"Material\": \"Solid\",\n");
            }
            json.append("  \"Group\": \"@Tech\",\n")
                    .append("  \"DrawType\": \"Model\",\n")
                    .append("  \"Opacity\": \"Transparent\",\n")
                    .append("  \"CustomModel\": \"").append(model).append("\",\n")
                    .append("  \"CustomModelScale\": ").append(scaleFactor).append(",\n")
                    .append("  \"CustomModelTexture\": [\n")
                    .append("    {\n")
                    .append("      \"Texture\": \"").append(texturePath).append("\"\n")
                    .append("    }\n")
                    .append("  ],\n");
            if (collision) {
                // Collision enabled: all tiles use normal Panel hitbox
                json.append("  \"HitboxType\": \"Panel\",\n");
            } else if (isBottomLeft) {
                // Collision disabled AND bottom-left: normal Panel hitbox (needed for Support)
                json.append("  \"HitboxType\": \"Panel\",\n");
            } else {
                // Collision disabled AND NOT bottom-left: thin hitbox for interaction only
                json.append("  \"HitboxType\": \"Panel_NoCollision\",\n");
            }
            if (!collision && isBottomLeft) {
                // Bottom-left tile needs Support section when collision is disabled - support from all directions
                json.append("  \"Support\": {\n")
                        .append("    \"Down\": [\n")
                        .append("      {\n")
                        .append("        \"FaceType\": \"Full\"\n")
                        .append("      }\n")
                        .append("    ],\n")
                        .append("    \"North\": [\n")
                        .append("      {\n")
                        .append("        \"FaceType\": \"Full\"\n")
                        .append("      }\n")
                        .append("    ],\n")
                        .append("    \"South\": [\n")
                        .append("      {\n")
                        .append("        \"FaceType\": \"Full\"\n")
                        .append("      }\n")
                        .append("    ],\n")
                        .append("    \"East\": [\n")
                        .append("      {\n")
                        .append("        \"FaceType\": \"Full\"\n")
                        .append("      }\n")
                        .append("    ],\n")
                        .append("    \"West\": [\n")
                        .append("      {\n")
                        .append("        \"FaceType\": \"Full\"\n")
                        .append("      }\n")
                        .append("    ],\n")
                        .append("    \"Up\": [\n")
                        .append("      {\n")
                        .append("        \"FaceType\": \"Full\"\n")
                        .append("      }\n")
                        .append("    ]\n")
                        .append("  },\n");
            }
            json.append("  \"VariantRotation\": \"NESW\",\n")
                    .append("  \"Gathering\": {\n")
                    .append("    \"Soft\": {\n")
                    .append("      \"IsWeaponBreakable\": false\n")
                    .append("    }\n")
                    .append("  },\n");
            json.append("  \"BlockParticleSetId\": \"Wood\",\n")
                    .append("  \"BlockSoundSetId\": \"Wood\",\n")
                    .append("  \"ParticleColor\": \"#684127\"\n")
                    .append("}\n");
            return json.toString();
        }
        if (SLIM_BLOCK_ID.equals(blockId)) {
            // For slim frames, use custom model
            // Use single image texture that wraps the model
            String model = "Blocks/ImageFrames/Slim_Frame.blockymodel";

            StringBuilder json = new StringBuilder();
            json.append("{\n")
                    .append("  \"CustomModel\": \"").append(model).append("\",\n")
                    .append("  \"CustomModelTexture\": [\n")
                    .append("    {\n")
                    .append("      \"Texture\": \"").append(texturePath).append("\"\n")
                    .append("    }\n")
                    .append("  ],\n")
                    .append("  \"Group\": \"@Tech\",\n")
                    .append("  \"Gathering\": {\n")
                    .append("    \"Breaking\": {\n")
                    .append("      \"Drops\": [\n")
                    .append("        {\n")
                    .append("          \"Item\": \"").append(SLIM_BLOCK_ID).append("\"\n")
                    .append("        }\n")
                    .append("      ]\n")
                    .append("    }\n")
                    .append("  },\n")
                    .append("  \"Tags\": {\n")
                    .append("    \"Type\": [\"Wood\"]\n")
                    .append("  },\n")
                    .append("  \"Material\": \"Solid\",\n")
                    .append("  \"DrawType\": \"Model\",\n")
                    .append("  \"Opacity\": \"Transparent\",\n")
                    .append("  \"HitboxType\": \"Panel\",\n")
                    .append("  \"VariantRotation\": \"NESW\",\n")
                    .append("  \"CubeShadingMode\": \"Standard\",\n")
                    .append("  \"BlockSoundSetId\": \"Wood\",\n")
                    .append("  \"BlockParticleSetId\": \"Wood\",\n")
                    .append("  \"ParticleColor\": \"#888888\"\n")
                    .append("}\n");
            return json.toString();
        }
        String faceTexture = texturePath;
        String up = FRAME_TEXTURE_PATH;
        String down = FRAME_TEXTURE_PATH;
        String north = FRAME_TEXTURE_PATH;
        String south = FRAME_TEXTURE_PATH;
        String east = FRAME_TEXTURE_PATH;
        String west = FRAME_TEXTURE_PATH;

        String resolvedFace = resolveTextureFace(normalAxis, facing);
        if ("Up".equals(resolvedFace)) {
            up = faceTexture;
        } else if ("Down".equals(resolvedFace)) {
            down = faceTexture;
        } else if ("North".equals(resolvedFace)) {
            north = faceTexture;
        } else if ("South".equals(resolvedFace)) {
            south = faceTexture;
        } else if ("East".equals(resolvedFace)) {
            east = faceTexture;
        } else if ("West".equals(resolvedFace)) {
            west = faceTexture;
        }

        StringBuilder json = new StringBuilder();
        json.append("{\n")
                .append("  \"Textures\": [\n")
                .append("    {\n")
                .append("      \"Up\": \"").append(up).append("\",\n")
                .append("      \"Down\": \"").append(down).append("\",\n")
                .append("      \"North\": \"").append(north).append("\",\n")
                .append("      \"South\": \"").append(south).append("\",\n")
                .append("      \"East\": \"").append(east).append("\",\n")
                .append("      \"West\": \"").append(west).append("\"\n")
                .append("    }\n")
                .append("  ],\n")
                .append("  \"Group\": \"@Tech\",\n")
                .append("  \"Gathering\": {\n")
                .append("    \"Breaking\": {\n")
                .append("      \"GatherType\": \"Woods\"\n")
                .append("    }\n")
                .append("  },\n")
                .append("  \"Tags\": {\n")
                .append("    \"Type\": [\"Wood\"]\n")
                .append("  },\n")
                .append("  \"Material\": \"Solid\",\n")
                .append("  \"DrawType\": \"Cube\",\n")
                .append("  \"BlockSoundSetId\": \"Wood\",\n")
                .append("  \"BlockParticleSetId\": \"Wood\",\n")
                .append("  \"ParticleColor\": \"#888888\"");
        json.append("\n}\n");
        return json.toString();
    }

    private String resolveTextureFace(Axis normalAxis, String facing) {
        if (facing != null) {
            String f = facing.trim().toLowerCase();
            if (normalAxis == Axis.X && (f.equals("west") || f.equals("east"))) {
                return f.substring(0, 1).toUpperCase() + f.substring(1);
            }
            if (normalAxis == Axis.Z && (f.equals("north") || f.equals("south"))) {
                return f.substring(0, 1).toUpperCase() + f.substring(1);
            }
            if (normalAxis == Axis.Y && (f.equals("up") || f.equals("down"))) {
                return f.substring(0, 1).toUpperCase() + f.substring(1);
            }
        }
        if (normalAxis == Axis.X) {
            return "West";
        }
        if (normalAxis == Axis.Y) {
            return "Up";
        }
        return "South";
    }

    public String resolveFacing(GroupInfo info, Vector4d hitLocation, Vector3i targetBlock, Vector3d playerPos) {
        if (info == null) {
            return "North";
        }
        String hitFacing = resolveFacingFromHit(info, hitLocation, targetBlock);
        if (hitFacing != null) {
            return hitFacing;
        }
        String playerFacing = resolveFacingFromPlayer(info, playerPos);
        if (playerFacing != null) {
            return playerFacing;
        }
        return defaultFacingForAxis(info.normalAxis);
    }

    private String resolveFacingFromHit(GroupInfo info, Vector4d hitLocation, Vector3i targetBlock) {
        if (hitLocation == null || targetBlock == null) {
            return null;
        }
        return switch (info.normalAxis) {
            case X -> hitLocation.x >= targetBlock.getX() + 0.5 ? "East" : "West";
            case Y -> hitLocation.y >= targetBlock.getY() + 0.5 ? "Up" : "Down";
            case Z -> hitLocation.z >= targetBlock.getZ() + 0.5 ? "South" : "North";
        };
    }

    private String resolveFacingFromPlayer(GroupInfo info, Vector3d playerPos) {
        if (playerPos == null) {
            return null;
        }
        return switch (info.normalAxis) {
            case X -> playerPos.x >= info.minX + 0.5 ? "East" : "West";
            case Y -> playerPos.y >= info.minY + 0.5 ? "Up" : "Down";
            case Z -> playerPos.z >= info.minZ + 0.5 ? "South" : "North";
        };
    }

    public static Axis getAxisFromHit(Vector4d hit, Vector3i target) {
        if (hit == null || target == null) {
            return null;
        }
        double rx = hit.x - (target.getX() + 0.5);
        double ry = hit.y - (target.getY() + 0.5);
        double rz = hit.z - (target.getZ() + 0.5);

        if (Math.abs(rx) > Math.abs(ry) && Math.abs(rx) > Math.abs(rz)) {
            return Axis.X;
        }
        if (Math.abs(ry) > Math.abs(rx) && Math.abs(ry) > Math.abs(rz)) {
            return Axis.Y;
        }
        return Axis.Z;
    }

    public static Axis getAxisFromRaycast(Vector3d start, Vector3d dir, Vector3i target) {
        if (start == null || dir == null || target == null) {
            return null;
        }

        double minX = target.getX();
        double maxX = target.getX() + 1.0;
        double minY = target.getY();
        double maxY = target.getY() + 1.0;
        double minZ = target.getZ();
        double maxZ = target.getZ() + 1.0;

        // Check if inside? Assume outside.
        // Slab method
        double t1 = (minX - start.x) / dir.x;
        double t2 = (maxX - start.x) / dir.x;
        double tNearX = Math.min(t1, t2);
        double tFarX = Math.max(t1, t2);

        double tNear = tNearX;
        double tFar = tFarX;

        double t3 = (minY - start.y) / dir.y;
        double t4 = (maxY - start.y) / dir.y;
        double tNearY = Math.min(t3, t4);
        double tFarY = Math.max(t3, t4);

        if (tNear > tFarY || tNearY > tFar) {
            return null; // Miss
        }
        tNear = Math.max(tNear, tNearY);
        tFar = Math.min(tFar, tFarY);

        double t5 = (minZ - start.z) / dir.z;
        double t6 = (maxZ - start.z) / dir.z;
        double tNearZ = Math.min(t5, t6);
        double tFarZ = Math.max(t5, t6);

        if (tNear > tFarZ || tNearZ > tFar) {
            return null; // Miss
        }
        tNear = Math.max(tNear, tNearZ);

        if (tNear < 0) {
            return null;
        }

        // Determine face by checking which tNear contributed to the final tNear
        // Allow a tiny epsilon for equality checks due to float precision
        double epsilon = 1e-6;
        if (Math.abs(tNear - tNearX) < epsilon)
            return Axis.X;
        if (Math.abs(tNear - tNearY) < epsilon)
            return Axis.Y;
        if (Math.abs(tNear - tNearZ) < epsilon)
            return Axis.Z;

        return null; // Should not happen if hit
    }

    private String defaultFacingForAxis(Axis axis) {
        if (axis == Axis.X) {
            return "West";
        }
        if (axis == Axis.Y) {
            return "Up";
        }
        return "South";
    }

    private Axis parseNormalAxisFromTileName(String baseName) {
        if (baseName == null) {
            return null;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile(".*_(\\d+)x(\\d+)x(\\d+)_\\d+_\\d+$")
                .matcher(baseName);
        if (!matcher.matches()) {
            return null;
        }
        try {
            int sizeX = Integer.parseInt(matcher.group(1));
            int sizeY = Integer.parseInt(matcher.group(2));
            if (sizeX == 1) {
                return Axis.X;
            }
            if (sizeY == 1) {
                return Axis.Y;
            }
            return Axis.Z;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static Vector3d getForwardVector(com.hypixel.hytale.math.vector.Vector3f rotation) {
        // Rotation is in Radians. Pitch: +PI/2 = Up, -PI/2 = Down. Yaw: 0 = -Z (North).
        // x = -sin(yaw) * cos(pitch)
        // y = sin(pitch)
        // z = -cos(yaw) * cos(pitch)

        double yaw = rotation.getYaw();
        double pitch = rotation.getPitch();

        double cosPitch = Math.cos(pitch);
        double x = -Math.sin(yaw) * cosPitch;
        double y = Math.sin(pitch);
        double z = -Math.cos(yaw) * cosPitch;

        return new Vector3d(x, y, z);
    }

    private String parseFacingFromTileName(String baseName) {
        if (baseName == null) {
            return null;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile(".*_\\d+x\\d+x\\d+_([A-Za-z]+)_[Rr]\\d+_\\d+_\\d+_\\d+$")
                .matcher(baseName);
        if (!matcher.matches()) {
            return null;
        }
        return matcher.group(1);
    }

    private void placeTiles(World world, GroupInfo info, FrameGroup group, Map<Vector3i, Integer> rotations) {
        String safeId = group.safeId != null && !group.safeId.isEmpty() ? group.safeId
                : sanitizeFilename(group.groupId);
        String facing = group.facing != null ? group.facing : "North";
        boolean isPanel = PANEL_BLOCK_ID.equals(group.blockId) || PANEL_INVISIBLE_BLOCK_ID.equals(group.blockId);

        for (int ty = 0; ty < info.height; ty++) {
            for (int tx = 0; tx < info.width; tx++) {
                Vector3i pos = info.toWorldPos(tx, ty, facing);
                String key = TILE_PREFIX + safeId + "_" + tx + "_" + ty;

                if (isPanel && rotations != null && rotations.containsKey(pos)) {
                    // Use stored rotation from ORIGINAL block (read before placePlaceholder)
                    int rotation = rotations.get(pos);
                    plugin.getLogger().at(Level.INFO).log("Placing tile at %d,%d,%d with stored rotation %d", pos.getX(), pos.getY(), pos.getZ(), rotation);
                    try {
                        long chunkIndex = com.hypixel.hytale.math.util.ChunkUtil.indexChunkFromBlock(pos.getX(), pos.getZ());
                        var chunk = world.getChunk(chunkIndex);
                        if (chunk != null) {
                            int blockId = BlockType.getAssetMap().getIndex(key);
                            BlockType blockType = BlockType.getAssetMap().getAsset(blockId);
                            if (blockType != null) {
                                chunk.setBlock(pos.getX(), pos.getY(), pos.getZ(), blockId, blockType, rotation, 0, 0);
                                plugin.getLogger().at(Level.INFO).log("Successfully placed tile with rotation %d", rotation);
                                continue;
                            }
                        }
                    } catch (Exception e) {
                        plugin.getLogger().at(Level.WARNING).withCause(e).log("Failed to place tile with rotation at %d,%d,%d", pos.getX(), pos.getY(), pos.getZ());
                    }
                } else if (isPanel) {
                    plugin.getLogger().at(Level.WARNING).log("No stored rotation for panel at %d,%d,%d, using world.setBlock", pos.getX(), pos.getY(), pos.getZ());
                }
                // Fallback to world.setBlock
                world.setBlock(pos.getX(), pos.getY(), pos.getZ(), key);
            }
        }
    }

    private static BufferedImage downloadImage(String url) throws IOException {
        if (url == null || url.isEmpty()) {
            throw new IOException("URL is empty");
        }
        URL u = new URL(url);
        HttpURLConnection conn = (HttpURLConnection) u.openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("User-Agent", "ImageFrames/1.0");
        conn.connect();
        if (conn.getResponseCode() >= 400) {
            throw new IOException("HTTP " + conn.getResponseCode());
        }
        BufferedImage img = ImageIO.read(conn.getInputStream());
        if (img == null) {
            throw new IOException("Unsupported image");
        }
        return img;
    }

    private static BufferedImage scaleImage(BufferedImage src, int targetW, int targetH, String fit) {
        if (targetW <= 0 || targetH <= 0) {
            return src;
        }
        if ("crop".equalsIgnoreCase(fit)) {
            double scale = Math.max(targetW / (double) src.getWidth(), targetH / (double) src.getHeight());
            int scaledW = Math.max(1, (int) Math.round(src.getWidth() * scale));
            int scaledH = Math.max(1, (int) Math.round(src.getHeight() * scale));
            BufferedImage scaled = resize(src, scaledW, scaledH);
            int x = Math.max(0, (scaledW - targetW) / 2);
            int y = Math.max(0, (scaledH - targetH) / 2);
            return scaled.getSubimage(x, y, targetW, targetH);
        }
        if ("contain".equalsIgnoreCase(fit) || "letterbox".equalsIgnoreCase(fit) || "fit".equalsIgnoreCase(fit)) {
            double scale = Math.min(targetW / (double) src.getWidth(), targetH / (double) src.getHeight());
            int scaledW = Math.max(1, (int) Math.round(src.getWidth() * scale));
            int scaledH = Math.max(1, (int) Math.round(src.getHeight() * scale));
            BufferedImage scaled = resize(src, scaledW, scaledH);
            BufferedImage out = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = out.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            // Keep transparent background - buildPanelAtlas will ensure proper sizing to prevent wrapping
            int x = Math.max(0, (targetW - scaledW) / 2);
            int y = Math.max(0, (targetH - scaledH) / 2);
            g.drawImage(scaled, x, y, null);
            g.dispose();
            return out;
        }
        if ("contain_no_upscale".equalsIgnoreCase(fit) || "contain-noupscale".equalsIgnoreCase(fit)) {
            double scale = Math.min(targetW / (double) src.getWidth(), targetH / (double) src.getHeight());
            scale = Math.min(1.0, scale);
            int scaledW = Math.max(1, (int) Math.round(src.getWidth() * scale));
            int scaledH = Math.max(1, (int) Math.round(src.getHeight() * scale));
            BufferedImage scaled = resize(src, scaledW, scaledH);
            BufferedImage out = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = out.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int x = Math.max(0, (targetW - scaledW) / 2);
            int y = Math.max(0, (targetH - scaledH) / 2);
            g.drawImage(scaled, x, y, null);
            g.dispose();
            return out;
        }
        return resize(src, targetW, targetH);
    }

    private static BufferedImage resize(BufferedImage src, int w, int h) {
        if (src.getWidth() == w && src.getHeight() == h) {
            return src;
        }
        BufferedImage current = src;
        if (current.getType() != BufferedImage.TYPE_INT_ARGB) {
            current = drawScaled(current, current.getWidth(), current.getHeight(),
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        }
        int curW = current.getWidth();
        int curH = current.getHeight();
        if (w < curW || h < curH) {
            while (curW / 2 >= w && curH / 2 >= h) {
                int nextW = Math.max(w, curW / 2);
                int nextH = Math.max(h, curH / 2);
                current = drawScaled(current, nextW, nextH, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                curW = nextW;
                curH = nextH;
            }
        }
        if (curW != w || curH != h) {
            current = drawScaled(current, w, h, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        }
        return current;
    }

    private static BufferedImage drawScaled(BufferedImage src, int w, int h, Object interpolationHint) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolationHint);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return img;
    }

    private static BufferedImage rotate(BufferedImage src, int degrees) {
        int rot = ((degrees % 360) + 360) % 360;
        if (rot == 0) {
            return src;
        }
        double rads = Math.toRadians(rot);
        int w = src.getWidth();
        int h = src.getHeight();
        int newW = (rot == 90 || rot == 270) ? h : w;
        int newH = (rot == 90 || rot == 270) ? w : h;
        BufferedImage rotated = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = rotated.createGraphics();
        AffineTransform at = new AffineTransform();
        at.translate(newW / 2.0, newH / 2.0);
        at.rotate(rads);
        at.translate(-w / 2.0, -h / 2.0);
        g.setTransform(at);
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return rotated;
    }

    private Vector3i parsePosKey(String worldName, String posKey) {
        if (posKey == null) {
            return null;
        }
        String tail = posKey;
        if (worldName != null && !worldName.isEmpty()) {
            String prefix = worldName + ":";
            if (posKey.startsWith(prefix)) {
                tail = posKey.substring(prefix.length());
            }
        }
        String[] parts = tail.split(":");
        if (parts.length < 3) {
            return null;
        }
        int len = parts.length;
        try {
            int x = Integer.parseInt(parts[len - 3]);
            int y = Integer.parseInt(parts[len - 2]);
            int z = Integer.parseInt(parts[len - 1]);
            return new Vector3i(x, y, z);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BufferedImage applyFlips(BufferedImage src, boolean flipX, boolean flipY) {
        BufferedImage out = src;
        if (flipX) {
            out = flipHorizontal(out);
        }
        if (flipY) {
            out = flipVertical(out);
        }
        return out;
    }

    private static BufferedImage flipVertical(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        BufferedImage flipped = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = flipped.createGraphics();
        g.drawImage(src, 0, 0, w, h, 0, h, w, 0, null);
        g.dispose();
        return flipped;
    }

    private static BufferedImage flipHorizontal(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        BufferedImage flipped = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = flipped.createGraphics();
        g.drawImage(src, 0, 0, w, h, w, 0, 0, h, null);
        g.dispose();
        return flipped;
    }

    private BufferedImage applyFrameUnderlay(BufferedImage image, boolean hideFrame) {
        if (hideFrame) {
            return image;
        }
        if (image == null || !image.getColorModel().hasAlpha()) {
            return image;
        }
        BufferedImage frame = getFrameTexture();
        if (frame == null) {
            return image;
        }
        BufferedImage base = resizeNearest(frame, image.getWidth(), image.getHeight());
        BufferedImage out = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(base, 0, 0, null);
        g.drawImage(image, 0, 0, null);
        g.dispose();
        return out;
    }

    private BufferedImage buildPanelAtlas(BufferedImage image, boolean includeFrame) {
        if (image == null) {
            return null;
        }
        // For panels: create a square atlas matching the original Panel.blockymodel expectations
        // The model has nodes with size=tileSize and offset y=tileSize for the image
        // Atlas layout: (tileSize*2) x (tileSize*2) square
        // Frame node samples from (0,0) with size tileSize x tileSize (top-left quadrant)
        // Image node samples from (0,tileSize) with size tileSize x tileSize (bottom-left quadrant)

        // Use the configured tileSize - this MUST match what the model generation uses
        int tileSize = plugin.getConfig().getTileSize();
        int atlasSize = tileSize * 2;

        BufferedImage atlas = new BufferedImage(atlasSize, atlasSize, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = atlas.createGraphics();

        // Fill with transparent background first
        g.setComposite(java.awt.AlphaComposite.Clear);
        g.fillRect(0, 0, atlasSize, atlasSize);
        g.setComposite(java.awt.AlphaComposite.SrcOver);

        if (includeFrame) {
            BufferedImage panel = getPanelTexture();
            if (panel != null) {
                // Panel.png is 64x64 - scale the entire thing to atlas size
                // Use nearest neighbor for frame (no alpha, pixel art)
                BufferedImage scaledPanel = resizeNearest(panel, atlasSize, atlasSize);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                g.drawImage(scaledPanel, 0, 0, null);
            }
        }

        // The image goes in the bottom-left quadrant (0, tileSize) to (tileSize, 2*tileSize)
        // This matches the model's textureLayout offset of (0, tileSize) for the image node
        // Scale image to tileSize x tileSize - must match exactly
        // Use bicubic interpolation for images with alpha to properly blend transparent edges
        BufferedImage scaledImage;
        if (image.getWidth() == tileSize && image.getHeight() == tileSize) {
            scaledImage = image;
        } else {
            // Always use bicubic for image scaling to handle alpha properly
            scaledImage = resize(image, tileSize, tileSize);
        }

        // For invisible frames, clean up alpha pixels to prevent artifacts
        if (!includeFrame) {
            scaledImage = cleanAlphaPixels(scaledImage);
        }

        // For non-invisible frames, overlay frame texture behind the image to fix transparency issues
        if (includeFrame) {
            BufferedImage panel = getPanelTexture();
            if (panel != null) {
                // Extract the front face portion (top-left 32x32) and scale to tileSize
                BufferedImage frameFace = panel.getSubimage(0, 0, 32, 32);
                BufferedImage scaledFrameFace = resizeNearest(frameFace, tileSize, tileSize);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                // Draw frame texture behind the image
                g.drawImage(scaledFrameFace, 0, tileSize, null);
            }
        }

        // Use proper alpha compositing with quality rendering hints for smooth edges
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
        g.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER));
        // Draw image on top of frame texture (or directly for invisible frames)
        g.drawImage(scaledImage, 0, tileSize, null);
        g.dispose();
        return atlas;
    }

    private String ensurePanelModel(int tileSize, boolean hideFrame) {
        String suffix = hideFrame ? "_NoFrame" : "";
        String modelAssetPath = PANEL_MODEL_DIR + "Panel_" + tileSize + suffix + ".blockymodel";
        Path filePath = runtimeAssetsPath.resolve("Common").resolve(modelAssetPath);
        try {
            String json = buildPanelModelJson(tileSize, hideFrame);
            byte[] bytes = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            boolean changed = writeBytesIfChanged(filePath, bytes);
            boolean hasAsset = CommonAssetRegistry.hasCommonAsset(modelAssetPath);
            if (changed || !hasAsset) {
                registerCommonAsset(modelAssetPath, filePath, bytes);
                plugin.getLogger().at(Level.INFO).log("Generated panel model: %s (hideFrame=%b, changed=%b, hadAsset=%b)", modelAssetPath, hideFrame, changed, hasAsset);
            } else {
                plugin.getLogger().at(Level.FINE).log("Panel model already exists: %s (hideFrame=%b)", modelAssetPath, hideFrame);
            }
            // Verify the model JSON doesn't include frame node when hideFrame is true
            if (hideFrame && json.contains("\"name\": \"frame\"")) {
                plugin.getLogger().at(Level.SEVERE).log("ERROR: Model for hideFrame=true still contains frame node! Model path: %s", modelAssetPath);
            }
            if (!hideFrame && !json.contains("\"name\": \"frame\"")) {
                plugin.getLogger().at(Level.SEVERE).log("ERROR: Model for hideFrame=false missing frame node! Model path: %s", modelAssetPath);
            }
            return modelAssetPath;
        } catch (IOException e) {
            plugin.getLogger().at(Level.WARNING).withCause(e)
                    .log("Failed to write panel model for tileSize=%d, hideFrame=%b", tileSize, hideFrame);
            // Don't fall back to PANEL_MODEL_PATH when hideFrame is true - it has a frame!
            if (hideFrame) {
                plugin.getLogger().at(Level.SEVERE).log("Cannot use fallback model for invisible frame - model generation failed!");
                // Try to generate a simple no-frame model as emergency fallback
                try {
                    String emergencyJson = "{\"nodes\":[{\"id\":\"2\",\"name\":\"image\",\"position\":{\"x\":0,\"y\":" + (tileSize/2.0) + ",\"z\":" + (-14.5 / (32.0/tileSize)) + "},\"orientation\":{\"x\":0,\"y\":0,\"z\":0,\"w\":1},\"shape\":{\"type\":\"box\",\"offset\":{\"x\":0,\"y\":0,\"z\":0},\"stretch\":{\"x\":1,\"y\":1,\"z\":1},\"settings\":{\"isPiece\":false,\"size\":{\"x\":" + tileSize + ",\"y\":" + tileSize + ",\"z\":" + (0.1 / (32.0/tileSize)) + "},\"isStaticBox\":true},\"textureLayout\":{\"back\":{\"offset\":{\"x\":0,\"y\":" + tileSize + "},\"mirror\":{\"x\":false,\"y\":false},\"angle\":0,\"texture\":0},\"right\":{\"offset\":{\"x\":0,\"y\":" + tileSize + "},\"mirror\":{\"x\":false,\"y\":false},\"angle\":0,\"texture\":0},\"front\":{\"offset\":{\"x\":0,\"y\":" + tileSize + "},\"mirror\":{\"x\":false,\"y\":false},\"angle\":0,\"texture\":0},\"left\":{\"offset\":{\"x\":0,\"y\":" + tileSize + "},\"mirror\":{\"x\":false,\"y\":false},\"angle\":0,\"texture\":0},\"top\":{\"offset\":{\"x\":0,\"y\":" + tileSize + "},\"mirror\":{\"x\":true,\"y\":true},\"angle\":0,\"texture\":0},\"bottom\":{\"offset\":{\"x\":0,\"y\":" + tileSize + "},\"mirror\":{\"x\":true,\"y\":false},\"angle\":0,\"texture\":0}},\"unwrapMode\":\"custom\",\"visible\":true,\"doubleSided\":true,\"shadingMode\":\"flat\"}}],\"format\":\"prop\",\"lod\":\"auto\"}";
                    byte[] emergencyBytes = emergencyJson.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    writeBytesIfChanged(filePath, emergencyBytes);
                    registerCommonAsset(modelAssetPath, filePath, emergencyBytes);
                    plugin.getLogger().at(Level.WARNING).log("Created emergency no-frame model");
                    return modelAssetPath;
                } catch (Exception ex) {
                    plugin.getLogger().at(Level.SEVERE).withCause(ex).log("Emergency model creation also failed!");
                    return null;
                }
            }
            return PANEL_MODEL_PATH;
        }
    }

    private String buildPanelModelJson(int tileSize, boolean hideFrame) {
        // Model size must match tileSize to use the full texture resolution
        // Frame node: uses Panel.png portion (top half of atlas, y: 0 to tileSize-1)
        // Image node: uses image portion (bottom half of atlas, y: tileSize to 2*tileSize-1)
        // Positions: CustomModelScale scales both size AND positions
        // To get final y: 16 after scaling, we need: modelY * scaleFactor = 16
        // Therefore: modelY = 16 / scaleFactor = 16 / (32/tileSize) = tileSize / 2
        // Original model: size 32x32, position y:16, scale 1.0 -> final y:16 ✓
        // Dynamic model: size tileSize x tileSize, position y:tileSize/2, scale 32/tileSize -> final y:16 ✓
        int imageOffsetY = tileSize;
        double scaleFactor = 32.0 / tileSize;
        double centerY = tileSize / 2.0; // Accounts for CustomModelScale scaling positions
        // Match the example structure: positions and sizes in model space (not world space)
        // For tileSize=256: frame z=-124, image z=-116, frame size z=8, image size z=0.8
        // Scale these values proportionally for other tileSizes
        double frameZ = -124.0 * (tileSize / 256.0);
        double imageZ = hideFrame ? frameZ : (-116.0 * (tileSize / 256.0)); // Flush with wall when no frame
        double frameSizeZ = 8.0 * (tileSize / 256.0);
        double imageSizeZ = 0.8 * (tileSize / 256.0);
        StringBuilder json = new StringBuilder();
        json.append("{\n")
                .append("  \"nodes\": [\n");

        // Only add frame node if not hiding frame
        if (!hideFrame) {
            json.append("    {\n")
                .append("      \"id\": \"1\",\n")
                .append("      \"name\": \"frame\",\n")
                .append("      \"position\": {\"x\": 0, \"y\": ").append(centerY).append(", \"z\": ").append(frameZ).append("},\n")
                .append("      \"orientation\": {\"x\": 0, \"y\": 0, \"z\": 0, \"w\": 1},\n")
                .append("      \"shape\": {\n")
                .append("        \"type\": \"box\",\n")
                .append("        \"offset\": {\"x\": 0, \"y\": 0, \"z\": 0},\n")
                .append("        \"stretch\": {\"x\": 1, \"y\": 1, \"z\": 1},\n")
                .append("        \"settings\": {\n")
                .append("          \"isPiece\": false,\n")
                .append("          \"size\": {\"x\": ").append(tileSize).append(", \"y\": ").append(tileSize).append(", \"z\": ").append(frameSizeZ).append("},\n")
                .append("          \"isStaticBox\": true\n")
                .append("        },\n")
                .append("        \"textureLayout\": {\n")
                .append("          \"back\": {\n")
                .append("            \"offset\": {\"x\": 0, \"y\": 0},\n")
                .append("            \"mirror\": {\"x\": false, \"y\": false},\n")
                .append("            \"angle\": 0\n")
                .append("          },\n")
                .append("          \"right\": {\n")
                .append("            \"offset\": {\"x\": 0, \"y\": 0},\n")
                .append("            \"mirror\": {\"x\": false, \"y\": false},\n")
                .append("            \"angle\": 0\n")
                .append("          },\n")
                .append("          \"front\": {\n")
                .append("            \"offset\": {\"x\": 0, \"y\": 0},\n")
                .append("            \"mirror\": {\"x\": false, \"y\": false},\n")
                .append("            \"angle\": 0\n")
                .append("          },\n")
                .append("          \"left\": {\n")
                .append("            \"offset\": {\"x\": 0, \"y\": 0},\n")
                .append("            \"mirror\": {\"x\": false, \"y\": false},\n")
                .append("            \"angle\": 0\n")
                .append("          },\n")
                .append("          \"top\": {\n")
                .append("            \"offset\": {\"x\": 0, \"y\": 0},\n")
                .append("            \"mirror\": {\"x\": true, \"y\": true},\n")
                .append("            \"angle\": 0\n")
                .append("          },\n")
                .append("          \"bottom\": {\n")
                .append("            \"offset\": {\"x\": 0, \"y\": 0},\n")
                .append("            \"mirror\": {\"x\": true, \"y\": false},\n")
                .append("            \"angle\": 0\n")
                .append("          }\n")
                .append("        },\n")
                .append("        \"unwrapMode\": \"custom\",\n")
                .append("        \"visible\": true,\n")
                .append("        \"doubleSided\": false,\n")
                .append("        \"shadingMode\": \"flat\"\n")
                .append("      }\n")
                .append("    },\n");
        }

        json.append("    {\n")
                .append("      \"id\": \"2\",\n")
                .append("      \"name\": \"image\",\n")
                .append("      \"position\": {\"x\": 0, \"y\": ").append(centerY).append(", \"z\": ").append(imageZ).append("},\n")
                .append("      \"orientation\": {\"x\": 0, \"y\": 0, \"z\": 0, \"w\": 1},\n")
                .append("      \"shape\": {\n")
                .append("        \"type\": \"box\",\n")
                .append("        \"offset\": {\"x\": 0, \"y\": 0, \"z\": 0},\n")
                .append("        \"stretch\": {\"x\": 1, \"y\": 1, \"z\": 1},\n")
                .append("        \"settings\": {\n")
                .append("          \"isPiece\": false,\n")
                .append("          \"size\": {\"x\": ").append(tileSize).append(", \"y\": ").append(tileSize).append(", \"z\": ").append(imageSizeZ).append("},\n")
                .append("          \"isStaticBox\": true\n")
                .append("        },\n")
                .append("        \"textureLayout\": {\n")
                .append("          \"back\": {\n")
                .append("            \"offset\": {\"x\": ").append(imageOffsetY).append(", \"y\": 0},\n")
                .append("            \"mirror\": {\"x\": false, \"y\": false},\n")
                .append("            \"angle\": 0\n")
                .append("          },\n")
                .append("          \"right\": {\n")
                .append("            \"offset\": {\"x\": 0, \"y\": ").append(imageOffsetY).append("},\n")
                .append("            \"mirror\": {\"x\": false, \"y\": false},\n")
                .append("            \"angle\": 0\n")
                .append("          },\n")
                .append("          \"front\": {\n")
                .append("            \"offset\": {\"x\": 0, \"y\": ").append(imageOffsetY).append("},\n")
                .append("            \"mirror\": {\"x\": false, \"y\": false},\n")
                .append("            \"angle\": 0\n")
                .append("          },\n")
                .append("          \"left\": {\n")
                .append("            \"offset\": {\"x\": 0, \"y\": ").append(imageOffsetY).append("},\n")
                .append("            \"mirror\": {\"x\": false, \"y\": false},\n")
                .append("            \"angle\": 0\n")
                .append("          },\n")
                .append("          \"top\": {\n")
                .append("            \"offset\": {\"x\": 0, \"y\": ").append(imageOffsetY).append("},\n")
                .append("            \"mirror\": {\"x\": true, \"y\": true},\n")
                .append("            \"angle\": 0\n")
                .append("          },\n")
                .append("          \"bottom\": {\n")
                .append("            \"offset\": {\"x\": 0, \"y\": ").append(imageOffsetY).append("},\n")
                .append("            \"mirror\": {\"x\": true, \"y\": false},\n")
                .append("            \"angle\": 0\n")
                .append("          }\n")
                .append("        },\n")
                .append("        \"unwrapMode\": \"custom\",\n")
                .append("        \"visible\": true,\n")
                .append("        \"doubleSided\": false,\n")
                .append("        \"shadingMode\": \"flat\"\n")
                .append("      }\n")
                .append("    }\n")
                .append("  ],\n")
                .append("  \"format\": \"prop\",\n")
                .append("  \"lod\": \"auto\"\n")
                .append("}\n");
        return json.toString();
    }

    private BufferedImage getPanelTexture() {
        BufferedImage cached = panelTextureCache;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (panelTextureCache != null) {
                return panelTextureCache;
            }
            BufferedImage loaded = null;
            try (InputStream in = ImageFramesPlugin.class.getClassLoader()
                    .getResourceAsStream("Common/Blocks/ImageFrames/Panel.png")) {
                if (in != null) {
                    loaded = ImageIO.read(in);
                }
            } catch (IOException ignored) {
            }
            if (loaded == null) {
                Path fallback = Paths.get("src/main/resources/Common/Blocks/ImageFrames/Panel.png");
                if (Files.exists(fallback)) {
                    try {
                        loaded = ImageIO.read(fallback.toFile());
                    } catch (IOException ignored) {
                    }
                }
            }
            panelTextureCache = loaded;
            return loaded;
        }
    }

    private static BufferedImage cleanAlphaPixels(BufferedImage src) {
        // Remove or blend alpha pixels to prevent artifacts on invisible frames
        // Make pixels with low alpha fully transparent
        BufferedImage cleaned = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < src.getHeight(); y++) {
            for (int x = 0; x < src.getWidth(); x++) {
                int rgb = src.getRGB(x, y);
                int alpha = (rgb >>> 24) & 0xFF;
                // If alpha is below threshold (semi-transparent edge pixels), make fully transparent
                if (alpha < 128) {
                    cleaned.setRGB(x, y, 0x00000000); // Fully transparent
                } else {
                    cleaned.setRGB(x, y, rgb);
                }
            }
        }
        return cleaned;
    }

    private static BufferedImage resizeNearest(BufferedImage src, int w, int h) {
        if (src.getWidth() == w && src.getHeight() == h) {
            return src;
        }
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return img;
    }

    private BufferedImage getFrameTexture() {
        BufferedImage cached = frameTextureCache;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (frameTextureCache != null) {
                return frameTextureCache;
            }
            BufferedImage loaded = null;
            try (InputStream in = ImageFramesPlugin.class.getClassLoader()
                    .getResourceAsStream("Common/Blocks/ImageFrames/Frame.png")) {
                if (in != null) {
                    loaded = ImageIO.read(in);
                }
            } catch (IOException ignored) {
            }
            if (loaded == null) {
                Path fallback = Paths.get("src/main/resources/Common/Blocks/ImageFrames/Frame.png");
                if (Files.exists(fallback)) {
                    try {
                        loaded = ImageIO.read(fallback.toFile());
                    } catch (IOException ignored) {
                    }
                }
            }
            frameTextureCache = loaded;
            return loaded;
        }
    }

    private static byte[] encodePng(BufferedImage img) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(img, "png", baos);
            return baos.toByteArray();
        }
    }

    private static boolean writeBytesIfChanged(Path path, byte[] bytes) throws IOException {
        if (Files.exists(path)) {
            byte[] existing = Files.readAllBytes(path);
            if (java.util.Arrays.equals(existing, bytes)) {
                return false;
            }
        }
        Files.createDirectories(path.getParent());
        Files.write(path, bytes);
        return true;
    }

    private static boolean writeStringIfChanged(Path path, String content) throws IOException {
        byte[] bytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return writeBytesIfChanged(path, bytes);
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception ignored) {
        }
    }

    private static String sanitizeFilename(String input) {
        if (input == null || input.isEmpty()) {
            return "frame";
        }
        String sanitized = input.replaceAll("[^A-Za-z0-9._-]", "_");
        if (sanitized.length() > 80) {
            sanitized = sanitized.substring(0, 80);
        }
        return sanitized;
    }

    private String buildSafeGroupId(String url, GroupInfo info, String facing, int rot) {
        long now = System.currentTimeMillis();
        String urlHash = hashHex(url == null ? "" : url.trim());
        String size = info != null ? info.sizeX + "x" + info.sizeY + "x" + info.sizeZ : "0x0x0";
        String face = facing != null ? facing : "Unknown";
        String token = urlHash + "_" + size + "_" + face + "_r" + rot + "_" + now;
        return toTitleCaseToken(sanitizeFilename(token));
    }

    private String hashHex(String input) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-1");
            byte[] bytes = digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(input != null ? input.hashCode() : 0);
        }
    }

    private static String toTitleCaseToken(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        String[] parts = input.split("_");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.isEmpty()) {
                continue;
            }
            out.append(Character.toUpperCase(p.charAt(0)));
            if (p.length() > 1) {
                out.append(p.substring(1));
            }
            if (i < parts.length - 1) {
                out.append("_");
            }
        }
        return out.toString();
    }

    private static Path resolveRuntimeBasePath() {
        Path cwd = Paths.get("").toAbsolutePath();
        Path cwdName = cwd.getFileName();
        if (cwdName != null && "run".equalsIgnoreCase(cwdName.toString())) {
            return cwd;
        }
        Path runDir = cwd.resolve("run");
        if (Files.isDirectory(runDir)) {
            return runDir.toAbsolutePath();
        }
        return cwd;
    }

    private GroupInfo collectGroup(World world, Vector3i start, Axis preferredNormal) {
        String worldName = world.getName();
        String startBlockId = world.getBlockType(start).getId();
        boolean panelsOnly = isPanelBlockId(startBlockId);
        Set<String> visited = new HashSet<>();
        ArrayDeque<Vector3i> queue = new ArrayDeque<>();
        queue.add(start);

        int minX = start.getX();
        int minY = start.getY();
        int minZ = start.getZ();
        int maxX = start.getX();
        int maxY = start.getY();
        int maxZ = start.getZ();

        List<Vector3i> blocks = new ArrayList<>();

        while (!queue.isEmpty()) {
            Vector3i pos = queue.poll();
            String key = ImageFrameStore.toPosKey(worldName, pos.getX(), pos.getY(), pos.getZ());
            if (!visited.add(key)) {
                continue;
            }
            String blockId = world.getBlockType(pos).getId();
            if (panelsOnly) {
                if (!isPanelBlockId(blockId)) {
                    continue;
                }
            } else if (!BASE_BLOCK_ID.equals(blockId) && !SLIM_BLOCK_ID.equals(blockId)
                    && !PANEL_BLOCK_ID.equals(blockId) && !PANEL_INVISIBLE_BLOCK_ID.equals(blockId)) {
                continue;
            }
            blocks.add(pos);
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxY = Math.max(maxY, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());

            queue.add(new Vector3i(pos.getX() + 1, pos.getY(), pos.getZ()));
            queue.add(new Vector3i(pos.getX() - 1, pos.getY(), pos.getZ()));
            queue.add(new Vector3i(pos.getX(), pos.getY() + 1, pos.getZ()));
            queue.add(new Vector3i(pos.getX(), pos.getY() - 1, pos.getZ()));
            queue.add(new Vector3i(pos.getX(), pos.getY(), pos.getZ() + 1));
            queue.add(new Vector3i(pos.getX(), pos.getY(), pos.getZ() - 1));
        }

        int sizeX = maxX - minX + 1;
        int sizeY = maxY - minY + 1;
        int sizeZ = maxZ - minZ + 1;

        boolean flat = (sizeX == 1 || sizeY == 1 || sizeZ == 1);
        String detectedBlockId = world.getBlockType(start).getId();
        GroupInfo info = new GroupInfo(worldName, minX, minY, minZ, sizeX, sizeY, sizeZ, blocks, preferredNormal,
                detectedBlockId);
        int expected = info.width * info.height;
        info.valid = flat && blocks.size() == expected;
        return info;
    }

    private GroupInfo buildGroupInfoFromStore(FrameGroup group) {
        int sizeX = group.sizeX;
        int sizeY = group.sizeY;
        int sizeZ = group.sizeZ;
        List<Vector3i> blocks = new ArrayList<>();
        if (group.tileBlocks != null && !group.tileBlocks.isEmpty()) {
            for (String posKey : group.tileBlocks.keySet()) {
                Vector3i pos = parsePosKey(group.worldName, posKey);
                if (pos != null) {
                    blocks.add(pos);
                }
            }
        } else {
            for (int x = 0; x < sizeX; x++) {
                for (int y = 0; y < sizeY; y++) {
                    for (int z = 0; z < sizeZ; z++) {
                        blocks.add(new Vector3i(group.minX + x, group.minY + y, group.minZ + z));
                    }
                }
            }
        }
        GroupInfo info = new GroupInfo(group.worldName, group.minX, group.minY, group.minZ, sizeX, sizeY, sizeZ, blocks,
                null, group.blockId);
        int expected = info.width * info.height;
        info.valid = (info.sizeX == 1 || info.sizeY == 1 || info.sizeZ == 1) && blocks.size() == expected;
        return info;
    }

    private boolean isPanelBlockId(String blockId) {
        return PANEL_BLOCK_ID.equals(blockId) || PANEL_INVISIBLE_BLOCK_ID.equals(blockId);
    }

    public static class GroupInfo {
        public final String worldName;
        public final int minX;
        public final int minY;
        public final int minZ;
        public final int sizeX;
        public final int sizeY;
        public final int sizeZ;
        public final List<Vector3i> blocks;
        public boolean valid;
        public final int width;
        public final int height;
        public final Axis widthAxis;
        public final Axis heightAxis;
        public final Axis normalAxis;
        public final String blockId;

        GroupInfo(String worldName, int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ,
                List<Vector3i> blocks, Axis preferredNormal, String blockId) {
            this.worldName = worldName;
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
            this.blocks = blocks;

            Axis normal;
            if (preferredNormal != null) {
                // If the preferred normal is provided, we respect if the size is 1 (or allow it
                // to override ambiguous cases like 1x1x1)
                // If we have a 1-thick shape in that direction, we use it.
                if ((preferredNormal == Axis.X && sizeX == 1) ||
                        (preferredNormal == Axis.Y && sizeY == 1) ||
                        (preferredNormal == Axis.Z && sizeZ == 1)) {
                    normal = preferredNormal;
                } else {
                    // Fallback to old logic if hint is contradicting dimensions (e.g. 2x2x1
                    // preferring Z)
                    if (sizeX == 1) {
                        normal = Axis.X;
                    } else if (sizeY == 1) {
                        normal = Axis.Y;
                    } else {
                        normal = Axis.Z;
                    }
                }
            } else {
                if (sizeX == 1) {
                    normal = Axis.X;
                } else if (sizeY == 1) {
                    normal = Axis.Y;
                } else {
                    normal = Axis.Z;
                }
            }
            this.normalAxis = normal;
            this.blockId = blockId;

            if (normal == Axis.X) {
                this.widthAxis = Axis.Z;
                this.heightAxis = Axis.Y;
            } else if (normal == Axis.Z) {
                this.widthAxis = Axis.X;
                this.heightAxis = Axis.Y;
            } else {
                this.widthAxis = Axis.X;
                this.heightAxis = Axis.Z;
            }
            this.width = lengthForAxis(widthAxis);
            this.height = lengthForAxis(heightAxis);
        }

        int lengthForAxis(Axis axis) {
            return switch (axis) {
                case X -> sizeX;
                case Y -> sizeY;
                case Z -> sizeZ;
            };
        }

        Vector3i toWorldPos(int w, int h, String facing) {
            if (heightAxis == Axis.Y) {
                h = (height - 1) - h;
            }
            boolean flipW = false;
            boolean flipH = false;
            if (facing != null) {
                String f = facing.toLowerCase();
                if (normalAxis == Axis.Z && f.equals("north")) {
                    flipW = true;
                } else if (normalAxis == Axis.X && f.equals("east")) {
                    flipW = true;
                } else if (normalAxis == Axis.Y && f.equals("down")) {
                    flipW = true;
                }
            }
            if (flipW) {
                w = (width - 1) - w;
            }
            if (flipH) {
                h = (height - 1) - h;
            }
            int x = minX;
            int y = minY;
            int z = minZ;
            x += widthAxis == Axis.X ? w : 0;
            y += widthAxis == Axis.Y ? w : 0;
            z += widthAxis == Axis.Z ? w : 0;
            x += heightAxis == Axis.X ? h : 0;
            y += heightAxis == Axis.Y ? h : 0;
            z += heightAxis == Axis.Z ? h : 0;
            return new Vector3i(x, y, z);
        }
    }

    public enum Axis {
        X,
        Y,
        Z
    }
}
