package dev.jacobwasbeast.util;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class ImageLimitsTest {
    @Test
    void rejectsOverflowAndOversizedRenderBeforeAllocation() {
        assertDoesNotThrow(() -> ImageLimits.checkDimensions(4096, 4096));
        assertThrows(IOException.class, () -> ImageLimits.checkDimensions(4097, 4096));
        assertThrows(IOException.class, () -> ImageLimits.checkDimensions(Long.MAX_VALUE, Long.MAX_VALUE));
        assertThrows(IOException.class, () -> ImageLimits.checkDimensions(0, 1));
    }

    @Test
    void rejectsOversizedEncodedImageAndUnknownFormats() {
        assertThrows(IOException.class, () -> ImageLimits.read(
                new ByteArrayInputStream(new byte[ImageLimits.MAX_DOWNLOAD_BYTES + 1])));
        assertThrows(IOException.class, () -> ImageLimits.read(new ByteArrayInputStream(new byte[] {1, 2, 3})));
    }

    @Test
    void checksHeaderBeforeDecodingPixelsAndPreservesAlpha() throws Exception {
        BufferedImage source = new BufferedImage(4, 3, BufferedImage.TYPE_INT_ARGB);
        source.setRGB(1, 1, 0x80112233);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(source, "png", bytes);
        byte[] png = bytes.toByteArray();
        BufferedImage decoded = ImageLimits.read(new ByteArrayInputStream(png));
        assertEquals(0x80112233, decoded.getRGB(1, 1));
        // PNG's IHDR stores width and height before the pixel stream. No huge image is allocated.
        png[16] = 0x7f;
        assertThrows(IOException.class, () -> ImageLimits.read(new ByteArrayInputStream(png)));
    }
}
