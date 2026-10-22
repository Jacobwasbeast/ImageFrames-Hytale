package dev.jacobwasbeast.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.joml.Vector3i;
import org.junit.jupiter.api.Test;

class GroupInfoTest {
    @Test
    void mapsWallTilesAcrossSectionBoundariesAndNegativeHeight() {
        var info = new ImageFrameRuntimeManager.GroupInfo("test", 31, -33, -1,
                2, 2, 1, List.of(), ImageFrameRuntimeManager.Axis.Z, "image_frames_panel");
        assertEquals(new Vector3i(32, -32, -1), info.toWorldPos(0, 0, "North"));
        assertEquals(new Vector3i(31, -33, -1), info.toWorldPos(1, 1, "North"));
        assertEquals(new Vector3i(31, -32, -1), info.toWorldPos(0, 0, "South"));
    }
}
