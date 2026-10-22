package dev.jacobwasbeast.util;

import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.accessor.SectionReader;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockOperations;
import org.joml.Vector3i;

/** Block access on the world thread, without loading or generating missing sections. */
public final class FrameBlockAccess {
    private FrameBlockAccess() {
    }

    public static boolean isLoaded(World world, Vector3i pos) {
        return reader(world).hasStorageInMemory(pos.x, pos.y, pos.z);
    }

    public static BlockType getBlockType(World world, Vector3i pos) {
        SectionReader reader = reader(world);
        return reader.hasStorageInMemory(pos.x, pos.y, pos.z)
                ? BlockType.getAssetMap().getAsset(reader.getBlock(pos.x, pos.y, pos.z)) : null;
    }

    public static int getRotation(World world, Vector3i pos) {
        SectionReader reader = reader(world);
        return reader.hasStorageInMemory(pos.x, pos.y, pos.z)
                ? reader.getRotationIndex(pos.x, pos.y, pos.z) : -1;
    }

    public static void setBlock(World world, Vector3i pos, String key, int rotation) {
        requireWorldThread(world);
        var chunkStore = world.getChunkStore();
        var section = chunkStore.getChunkSectionReferenceAtBlock(pos.x, pos.y, pos.z);
        if (section == null || !section.isValid()) {
            throw new IllegalStateException("Frame section is not loaded at " + pos);
        }
        int id = BlockType.getAssetMap().getIndex(key);
        BlockType type = BlockType.getAssetMap().getAsset(id);
        if (type == null || !key.equals(type.getId())) {
            throw new IllegalArgumentException("Unknown frame block: " + key);
        }
        BlockOperations.setBlock(chunkStore, section, pos.x, pos.y, pos.z, id, type,
                Math.max(0, rotation), 0, 0);
    }

    private static SectionReader reader(World world) {
        requireWorldThread(world);
        return new SectionReader(world.getChunkStore());
    }

    private static void requireWorldThread(World world) {
        if (!world.isInThread()) {
            throw new IllegalStateException("Frame block access must run on the world thread");
        }
    }
}
