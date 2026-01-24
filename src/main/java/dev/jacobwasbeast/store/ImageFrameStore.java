package dev.jacobwasbeast.store;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.util.io.BlockingDiskFile;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class ImageFrameStore extends BlockingDiskFile {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Map<String, FrameGroup> groups = new HashMap<>();
    private final Map<String, String> posIndex = new HashMap<>();

    public ImageFrameStore() {
        super(Path.of("ImageFrames/frames.json"));
        ensureParent();
    }

    @Override
    protected void read(BufferedReader bufferedReader) throws IOException {
        Type type = new TypeToken<Map<String, FrameGroup>>() {}.getType();
        Map<String, FrameGroup> loaded = GSON.fromJson(bufferedReader, type);
        groups.clear();
        if (loaded != null) {
            groups.putAll(loaded);
        }
        rebuildIndex();
    }

    @Override
    protected void write(BufferedWriter bufferedWriter) throws IOException {
        if (getPath().getParent() != null) {
            java.nio.file.Files.createDirectories(getPath().getParent());
        }
        bufferedWriter.write(GSON.toJson(groups));
    }

    @Override
    protected void create(BufferedWriter bufferedWriter) throws IOException {
        if (getPath().getParent() != null) {
            java.nio.file.Files.createDirectories(getPath().getParent());
        }
        bufferedWriter.write("{}");
    }

    private java.nio.file.Path getPath() {
        return java.nio.file.Path.of("ImageFrames/frames.json");
    }

    private void ensureParent() {
        try {
            java.nio.file.Path parent = getPath().getParent();
            if (parent != null) {
                java.nio.file.Files.createDirectories(parent);
            }
        } catch (IOException ignored) {
        }
    }

    public synchronized void putGroup(FrameGroup group) {
        groups.put(group.groupId, group);
        rebuildIndex();
        syncSave();
    }

    public synchronized void removeGroup(String groupId) {
        groups.remove(groupId);
        rebuildIndex();
        syncSave();
    }

    public synchronized FrameGroup getGroup(String groupId) {
        return groups.get(groupId);
    }

    public synchronized FrameGroup getGroupByPos(String worldName, Vector3i pos) {
        String key = toPosKey(worldName, pos.getX(), pos.getY(), pos.getZ());
        String groupId = posIndex.get(key);
        return groupId != null ? groups.get(groupId) : null;
    }

    public synchronized Map<String, FrameGroup> getGroupsSnapshot() {
        return Collections.unmodifiableMap(new HashMap<>(groups));
    }

    public static String toPosKey(String worldName, int x, int y, int z) {
        return worldName + ":" + x + ":" + y + ":" + z;
    }

    private void rebuildIndex() {
        posIndex.clear();
        for (FrameGroup group : groups.values()) {
            if (group == null || group.tileBlocks == null) {
                continue;
            }
            for (String posKey : group.tileBlocks.keySet()) {
                posIndex.put(posKey, group.groupId);
            }
        }
    }

    public static class FrameGroup {
        public String groupId;
        public String safeId;
        public String worldName;
        public int minX;
        public int minY;
        public int minZ;
        public int sizeX;
        public int sizeY;
        public int sizeZ;
        public String ownerUuid;
        public String url;
        public String fit;
        public int rot;
        public String facing;
        public Map<String, String> tileBlocks = new HashMap<>();
        public transient Map<String, byte[]> tilePngByPath = new HashMap<>();

        public FrameGroup() {
        }

        public FrameGroup(String groupId) {
            this.groupId = groupId;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof FrameGroup other)) {
                return false;
            }
            return Objects.equals(groupId, other.groupId)
                    && Objects.equals(worldName, other.worldName)
                    && minX == other.minX
                    && minY == other.minY
                    && minZ == other.minZ
                    && sizeX == other.sizeX
                    && sizeY == other.sizeY
                    && sizeZ == other.sizeZ
                    && Objects.equals(ownerUuid, other.ownerUuid)
                    && Objects.equals(url, other.url)
                    && Objects.equals(fit, other.fit)
                    && rot == other.rot
                    && Objects.equals(tileBlocks, other.tileBlocks);
        }

        @Override
        public int hashCode() {
            return Objects.hash(groupId, worldName, minX, minY, minZ, sizeX, sizeY, sizeZ, ownerUuid, url, fit, rot, tileBlocks);
        }
    }
}
