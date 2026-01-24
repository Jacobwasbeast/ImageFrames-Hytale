package dev.jacobwasbeast.runtime;

import com.hypixel.hytale.common.plugin.PluginManifest;
import com.hypixel.hytale.common.semver.Semver;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.math.vector.Vector4d;
import com.hypixel.hytale.server.core.asset.AssetModule;
import com.hypixel.hytale.assetstore.AssetUpdateQuery;
import com.hypixel.hytale.server.core.asset.common.CommonAssetModule;
import com.hypixel.hytale.server.core.asset.common.CommonAssetRegistry;
import com.hypixel.hytale.server.core.asset.common.asset.FileCommonAsset;
import com.hypixel.hytale.protocol.Packet;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import javax.imageio.ImageIO;

public class ImageFrameRuntimeManager {
    public static final String BASE_BLOCK_ID = "image_frames:frame";
    public static final String TILE_PREFIX = "ImageFrames_Tile_";
    private static final String AIR_BLOCK_ID = "empty";

    private static final String RUNTIME_ASSETS_PACK = "ImageFramesRuntimeAssets";
    private static final String RUNTIME_ASSETS_DIR = "image_frames_assets";
    private static final String RUNTIME_BLOCKS_DIR = "Server/Item/Block/Blocks/ImageFramesData";
    private static final String FRAME_TEXTURE_PATH = "Blocks/ImageFrames/Frame.png";
    private static final String TILE_TEXTURE_DIR = "Blocks/ImageFrames/tiles/";
    private static final AssetUpdateQuery TILE_UPDATE_QUERY = new AssetUpdateQuery(
            new AssetUpdateQuery.RebuildCache(true, false, false, false, false, false));

    private final ImageFramesPlugin plugin;
    private final ImageFrameStore store;
    private final Path runtimeAssetsPath;
    private final Path runtimeCommonBlocksPath;
    private final Path runtimeBlockTypesPath;
    private volatile BufferedImage frameTextureCache;
    private final ImageFrameImageCache imageCache;

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
                        group.sizeX, group.sizeY, group.sizeZ, java.util.Collections.emptyList());
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
                    BooleanObjectPair<com.hypixel.hytale.server.core.asset.common.CommonAssetRegistry.PackAsset> removed =
                            CommonAssetRegistry.removeCommonAssetByName(RUNTIME_ASSETS_PACK, assetPath);
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
                var assetStore = (com.hypixel.hytale.server.core.asset.HytaleAssetStore<String, BlockType, com.hypixel.hytale.assetstore.map.BlockTypeAssetMap<String, BlockType>>) BlockType.getAssetStore();
                assetStore.removeAssetWithPaths(RUNTIME_ASSETS_PACK, removeJsonPaths, TILE_UPDATE_QUERY);
            } catch (Exception e) {
                plugin.getLogger().at(Level.WARNING).withCause(e).log("Failed to remove orphaned ImageFrames block types");
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
                        group.sizeX, group.sizeY, group.sizeZ, java.util.Collections.emptyList());
                BufferedImage source = loadSourceImage(group.url);
                rebuildGroupAssetsFromSource(info, group, source, blockTypePaths);
                store.putGroup(group);
            } catch (Exception e) {
                plugin.getLogger().at(Level.WARNING).withCause(e).log("Failed to rebuild ImageFrame assets for %s", group.groupId);
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
                    plugin.getLogger().at(Level.WARNING).withCause(e).log("Failed to register tile asset %s", assetPath);
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
        if (!Files.isDirectory(runtimeCommonBlocksPath)) {
            return;
        }
        try {
            Files.createDirectories(runtimeBlockTypesPath);
        } catch (IOException e) {
            plugin.getLogger().at(Level.WARNING).withCause(e).log("Failed to create runtime block type directory");
            return;
        }

        try (var stream = Files.list(runtimeCommonBlocksPath)) {
            stream.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".png")).forEach(path -> {
                String fileName = path.getFileName().toString();
                String baseName = fileName.substring(0, fileName.length() - 4);
                String tileKey = TILE_PREFIX + baseName;
                String assetPath = TILE_TEXTURE_DIR + fileName;
                Path jsonPath = runtimeBlockTypesPath.resolve(tileKey + ".json");
                try {
                    Axis normalAxis = parseNormalAxisFromTileName(baseName);
                    String facing = parseFacingFromTileName(baseName);
                    writeStringIfChanged(jsonPath, buildTileBlockTypeJson(assetPath, normalAxis, facing));
                } catch (IOException e) {
                    plugin.getLogger().at(Level.WARNING).withCause(e).log("Failed to write tile block type %s", tileKey);
                }
            });
        } catch (IOException e) {
            plugin.getLogger().at(Level.WARNING).withCause(e).log("Failed to scan runtime tiles for block types");
        }

        List<Path> jsonPaths = listJsonFiles(runtimeBlockTypesPath);
        loadBlockTypeAssets(jsonPaths);
    }

    public GroupInfo collectGroupInfo(World world, Vector3i target) throws IOException {
        GroupInfo info = collectGroup(world, target);
        if (!info.valid) {
            throw new IOException("Frames must be in a flat rectangle (e.g., 2x2, 3x3)");
        }
        return info;
    }

    public FrameGroup buildGroupAssets(GroupInfo info, String url, String fit, int rot, boolean flipX, boolean flipY,
            String ownerUuid, String facing)
            throws IOException {
        String groupId = info.worldName + ":" + info.minX + ":" + info.minY + ":" + info.minZ + ":" + info.sizeX + "x"
                + info.sizeY + "x" + info.sizeZ;
        String fileGroupId = buildSafeGroupId(url, info, facing, rot);

        BufferedImage source = loadSourceImage(url);
        int tileSize = plugin.getConfig().getTileSize();
        BufferedImage processed = scaleImage(source, info.width * tileSize, info.height * tileSize, fit);
        if (rot != 0) {
            processed = rotate(processed, rot);
        }
        processed = applyFlips(processed, flipX, flipY);
        processed = applyFrameUnderlay(processed);

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
        group.tileBlocks.clear();

        for (int ty = 0; ty < info.height; ty++) {
            for (int tx = 0; tx < info.width; tx++) {
                int px = tx * tileSize;
                int py = ty * tileSize;
                BufferedImage tile = processed.getSubimage(px, py, tileSize, tileSize);

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
                boolean jsonChanged = writeStringIfChanged(jsonPath, buildTileBlockTypeJson(assetPath, info.normalAxis, facing));
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

    private void rebuildGroupAssetsFromSource(GroupInfo info, FrameGroup group, BufferedImage source, List<Path> blockTypePaths)
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
        BufferedImage processed = scaleImage(source, info.width * tileSize, info.height * tileSize, fit);
        if (group.rot != 0) {
            processed = rotate(processed, group.rot);
        }
        processed = applyFlips(processed, group.flipX, group.flipY);
        processed = applyFrameUnderlay(processed);

        group.tileBlocks.clear();
        for (int ty = 0; ty < info.height; ty++) {
            for (int tx = 0; tx < info.width; tx++) {
                int px = tx * tileSize;
                int py = ty * tileSize;
                BufferedImage tile = processed.getSubimage(px, py, tileSize, tileSize);

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
                boolean jsonChanged = writeStringIfChanged(jsonPath, buildTileBlockTypeJson(assetPath, info.normalAxis, facing));
                if (jsonChanged || BlockType.getAssetMap().getAsset(tileKey) == null) {
                    blockTypePaths.add(jsonPath);
                }
                Vector3i pos = info.toWorldPos(tx, ty, facing);
                group.tileBlocks.put(ImageFrameStore.toPosKey(info.worldName, pos.getX(), pos.getY(), pos.getZ()), tileKey);
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

    public void applyGroup(World world, GroupInfo info, FrameGroup group) {
        placeTiles(world, info, group);
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
            scheduleBlockClear(world, positions, 256, () -> scheduleRemoveGroupAssets(group, 5));
        } else {
            scheduleRemoveGroupAssets(group, 5);
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
        String safeId = (group.safeId != null && !group.safeId.isEmpty())
                ? group.safeId
                : sanitizeFilename(group.groupId);
        GroupInfo info = new GroupInfo(group.worldName, group.minX, group.minY, group.minZ,
                group.sizeX, group.sizeY, group.sizeZ, java.util.Collections.emptyList());
        List<Path> jsonPaths = new ArrayList<>();
        List<com.hypixel.hytale.server.core.asset.common.CommonAssetRegistry.PackAsset> removedCommon = new ArrayList<>();
        for (int ty = 0; ty < info.height; ty++) {
            for (int tx = 0; tx < info.width; tx++) {
                String baseName = safeId + "_" + tx + "_" + ty;
                String assetPath = TILE_TEXTURE_DIR + baseName + ".png";
                Path pngPath = runtimeCommonBlocksPath.resolve(baseName + ".png");
                Path jsonPath = runtimeBlockTypesPath.resolve(TILE_PREFIX + baseName + ".json");

                BooleanObjectPair<com.hypixel.hytale.server.core.asset.common.CommonAssetRegistry.PackAsset> removed =
                        CommonAssetRegistry.removeCommonAssetByName(RUNTIME_ASSETS_PACK, assetPath);
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
                var assetStore = (com.hypixel.hytale.server.core.asset.HytaleAssetStore<String, BlockType, com.hypixel.hytale.assetstore.map.BlockTypeAssetMap<String, BlockType>>) BlockType.getAssetStore();
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

    public void placePlaceholder(World world, GroupInfo info) {
        if (world == null || info == null || info.blocks == null) {
            return;
        }
        for (Vector3i pos : info.blocks) {
            world.setBlock(pos.getX(), pos.getY(), pos.getZ(), BASE_BLOCK_ID);
        }
    }

    public void applyGroupWhenReady(World world, GroupInfo info, FrameGroup group, int remaining,
            Runnable onSuccess, Runnable onTimeout) {
        com.hypixel.hytale.server.core.HytaleServer.SCHEDULED_EXECUTOR
                .schedule(() -> world.execute(() -> {
                    if (areTileBlockTypesReady(info, group)) {
                        com.hypixel.hytale.server.core.HytaleServer.SCHEDULED_EXECUTOR
                                .schedule(() -> world.execute(() -> {
                                    placeTiles(world, info, group);
                                    store.putGroup(group);
                                }), 5000, java.util.concurrent.TimeUnit.MILLISECONDS);
                        if (onSuccess != null) {
                            onSuccess.run();
                        }
                        return;
                    }
                    if (remaining <= 0) {
                        if (onTimeout != null) {
                            onTimeout.run();
                        }
                        return;
                    }
                    applyGroupWhenReady(world, info, group, remaining - 1, onSuccess, onTimeout);
                }), 250, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    public boolean areTileBlockTypesReady(GroupInfo info, FrameGroup group) {
        String safeId = group.safeId != null && !group.safeId.isEmpty() ? group.safeId : sanitizeFilename(group.groupId);
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
        ensureCommonAssetsRegistered();
        java.util.Map<String, BlockType> loaded = new java.util.HashMap<>();
        for (FrameGroup group : store.getGroupsSnapshot().values()) {
            if (group == null || group.tileBlocks == null) {
                continue;
            }
            for (String key : group.tileBlocks.values()) {
                BlockType bt = BlockType.getAssetMap().getAsset(key);
                if (bt != null) {
                    loaded.put(key, bt);
                }
            }
        }
        if (loaded.isEmpty()) {
            plugin.getLogger().at(java.util.logging.Level.INFO).log("No ImageFrames block types to broadcast");
            return;
        }
        @SuppressWarnings("unchecked")
        var assetStore = (com.hypixel.hytale.server.core.asset.HytaleAssetStore<String, BlockType, com.hypixel.hytale.assetstore.map.BlockTypeAssetMap<String, BlockType>>) BlockType.getAssetStore();
        Packet packet = assetStore.getPacketGenerator().generateUpdatePacket(assetStore.getAssetMap(), loaded, TILE_UPDATE_QUERY);
        com.hypixel.hytale.server.core.universe.Universe.get().broadcastPacketNoCache(packet);
        plugin.getLogger().at(java.util.logging.Level.INFO).log("Broadcasted %d ImageFrames block types", loaded.size());
    }

    public void broadcastGroupAssets(FrameGroup group) {
        if (group == null || group.tileBlocks == null || group.tileBlocks.isEmpty()) {
            return;
        }

        java.util.Map<String, BlockType> loaded = new java.util.HashMap<>();
        for (String key : group.tileBlocks.values()) {
            BlockType bt = BlockType.getAssetMap().getAsset(key);
            if (bt != null) {
                loaded.put(key, bt);
            }
        }
        if (loaded.isEmpty()) {
            return;
        }
        @SuppressWarnings("unchecked")
        var assetStore = (com.hypixel.hytale.server.core.asset.HytaleAssetStore<String, BlockType, com.hypixel.hytale.assetstore.map.BlockTypeAssetMap<String, BlockType>>) BlockType.getAssetStore();
        Packet packet = assetStore.getPacketGenerator().generateUpdatePacket(assetStore.getAssetMap(), loaded, TILE_UPDATE_QUERY);
        com.hypixel.hytale.server.core.universe.Universe.get().broadcastPacketNoCache(packet);
        plugin.getLogger().at(java.util.logging.Level.INFO).log("Broadcasted ImageFrames group block types: %d", loaded.size());
    }

    public void broadcastCommonAssets() {
        ensureCommonAssetsRegistered();
        CommonAssetModule commonAssetModule = CommonAssetModule.get();
        if (commonAssetModule == null) {
            return;
        }
        java.util.List<com.hypixel.hytale.server.core.asset.common.CommonAsset> assets =
                CommonAssetRegistry.getCommonAssetsStartingWith(RUNTIME_ASSETS_PACK, "Blocks/ImageFrames/tiles/");
        if (assets == null || assets.isEmpty()) {
            return;
        }
        commonAssetModule.sendAssets(assets, false);
        plugin.getLogger().at(java.util.logging.Level.INFO).log("Broadcasted %d ImageFrames textures", assets.size());
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
            var assetStore = (com.hypixel.hytale.server.core.asset.HytaleAssetStore<String, BlockType, com.hypixel.hytale.assetstore.map.BlockTypeAssetMap<String, BlockType>>) BlockType.getAssetStore();
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

    private String buildTileBlockTypeJson(String texturePath, Axis normalAxis, String facing) {
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
                .append("  \"Group\": \"Wood\",\n")
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
            int sizeZ = Integer.parseInt(matcher.group(3));
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

    private void placeTiles(World world, GroupInfo info, FrameGroup group) {
        String safeId = group.safeId != null && !group.safeId.isEmpty() ? group.safeId : sanitizeFilename(group.groupId);
        String facing = group.facing != null ? group.facing : "North";
        for (int ty = 0; ty < info.height; ty++) {
            for (int tx = 0; tx < info.width; tx++) {
                Vector3i pos = info.toWorldPos(tx, ty, facing);
                String key = TILE_PREFIX + safeId + "_" + tx + "_" + ty;
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
            current = drawScaled(current, current.getWidth(), current.getHeight(), RenderingHints.VALUE_INTERPOLATION_BICUBIC);
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


    private BufferedImage applyFrameUnderlay(BufferedImage image) {
        if (image == null || !image.getColorModel().hasAlpha()) {
            return image;
        }
        BufferedImage frame = getFrameTexture();
        if (frame == null) {
            return image;
        }
        BufferedImage base = resize(frame, image.getWidth(), image.getHeight());
        BufferedImage out = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(base, 0, 0, null);
        g.drawImage(image, 0, 0, null);
        g.dispose();
        return out;
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

    private GroupInfo collectGroup(World world, Vector3i start) {
        String worldName = world.getName();
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
            if (!isFrameBlock(blockId)) {
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
        GroupInfo info = new GroupInfo(worldName, minX, minY, minZ, sizeX, sizeY, sizeZ, blocks);
        int expected = info.width * info.height;
        info.valid = flat && blocks.size() == expected;
        return info;
    }

    private static boolean isFrameBlock(String blockId) {
        return BASE_BLOCK_ID.equals(blockId) || (blockId != null && blockId.startsWith(TILE_PREFIX));
    }

    public static class GroupInfo {
        final String worldName;
        final int minX;
        final int minY;
        final int minZ;
        final int sizeX;
        final int sizeY;
        final int sizeZ;
        final List<Vector3i> blocks;
        boolean valid;
        final int width;
        final int height;
        final Axis widthAxis;
        final Axis heightAxis;
        final Axis normalAxis;

        GroupInfo(String worldName, int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ, List<Vector3i> blocks) {
            this.worldName = worldName;
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
            this.blocks = blocks;

            Axis normal;
            if (sizeX == 1) {
                normal = Axis.X;
            } else if (sizeY == 1) {
                normal = Axis.Y;
            } else {
                normal = Axis.Z;
            }
            this.normalAxis = normal;

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

    private enum Axis {
        X,
        Y,
        Z
    }
}
