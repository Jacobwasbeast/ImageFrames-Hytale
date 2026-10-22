package dev.jacobwasbeast.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImageFrameImageCacheTest {
    @TempDir Path directory;

    @Test
    void evictsOldImagesButCanRestoreThemFromDisk() throws Exception {
        ImageFrameImageCache cache = new ImageFrameImageCache(directory);
        BufferedImage first = new BufferedImage(2048, 2048, BufferedImage.TYPE_INT_ARGB);
        BufferedImage second = new BufferedImage(2048, 2048, BufferedImage.TYPE_INT_ARGB);
        BufferedImage third = new BufferedImage(2048, 2048, BufferedImage.TYPE_INT_ARGB);
        cache.store("first", first);
        cache.store("second", second);
        // Touch first: second must be the entry evicted when third is added.
        assertSame(first, cache.loadOrDownload("first", () -> fail("Unexpected download")));
        cache.store("third", third);
        assertSame(first, cache.loadOrDownload("first", () -> fail("Unexpected download")));
        assertNotSame(second, cache.loadOrDownload("second", () -> fail("Unexpected download")));
        cache.clearMemory();
        assertNotSame(first, cache.loadOrDownload("first", () -> fail("Unexpected download")));
    }
}
