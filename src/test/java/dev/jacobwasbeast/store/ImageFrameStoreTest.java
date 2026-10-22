package dev.jacobwasbeast.store;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.StringReader;
import java.nio.file.Path;
import org.joml.Vector3i;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImageFrameStoreTest {
    @TempDir Path directory;

    @Test
    void migratesLegacyItemIdsWithoutChangingSavedTilePositions() throws Exception {
        ImageFrameStore store = new ImageFrameStore(directory.resolve("frames.json"));
        for (String kind : new String[] {"frame", "panel", "banner"}) {
            String json = "{\"saved\":{\"groupId\":\"saved\",\"worldName\":\"world\","
                    + "\"blockId\":\"image_frames:" + kind + "\",\"tileBlocks\":{\"world:31:-33:-1\":\"ImageFrames_Tile_saved_0_0\"}}}";
            store.read(new BufferedReader(new StringReader(json)));
            var group = store.getGroupByPos("world", new Vector3i(31, -33, -1));
            assertNotNull(group);
            assertEquals("image_frames_" + kind, group.blockId);
            assertEquals("ImageFrames_Tile_saved_0_0", group.tileBlocks.get("world:31:-33:-1"));
        }
    }
}
