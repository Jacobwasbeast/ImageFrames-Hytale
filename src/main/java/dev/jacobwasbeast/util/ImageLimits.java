package dev.jacobwasbeast.util;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/** Bounds both the compressed download and allocations before decoding or rendering. */
public final class ImageLimits {
    public static final int MAX_DOWNLOAD_BYTES = 16 * 1024 * 1024;
    public static final long MAX_PIXELS = 16L * 1024 * 1024;
    public static final long MAX_CACHE_BYTES = 64L * 1024 * 1024;
    public static final int MAX_TILES = 256;
    public static final int MAX_TILE_SIZE = 1024;

    private ImageLimits() {
    }

    public static void checkDimensions(long width, long height) throws IOException {
        if (width <= 0 || height <= 0 || width > MAX_PIXELS || height > MAX_PIXELS
                || width > MAX_PIXELS / height) {
            throw new IOException("Image dimensions exceed the 16 megapixel limit");
        }
    }

    public static BufferedImage read(Path path) throws IOException {
        // Locally re-encoding a compressed source as PNG may produce a larger cache file.
        int cacheLimit = (int) (MAX_PIXELS * 4 + 1024 * 1024);
        if (Files.size(path) > cacheLimit) throw new IOException("Cached image exceeds the size limit");
        try (InputStream input = Files.newInputStream(path)) {
            return read(input, cacheLimit);
        }
    }

    public static BufferedImage read(InputStream input) throws IOException {
        return read(input, MAX_DOWNLOAD_BYTES);
    }

    private static BufferedImage read(InputStream input, int byteLimit) throws IOException {
        byte[] bytes = input.readNBytes(byteLimit + 1);
        if (bytes.length > byteLimit) throw new IOException("Image exceeds the encoded size limit");
        try (ImageInputStream imageInput = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(imageInput);
            if (!readers.hasNext()) throw new IOException("Unsupported image");
            ImageReader reader = readers.next();
            try {
                reader.setInput(imageInput, true, true);
                checkDimensions(reader.getWidth(0), reader.getHeight(0));
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        }
    }
}
