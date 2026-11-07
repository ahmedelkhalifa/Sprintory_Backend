package team.sprintory_backend.attachment.services;

import com.luciad.imageio.webp.WebPImageReaderSpi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import team.sprintory_backend.attachment.models.AttachmentType;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageOptimizerTest {

    private final ImageOptimizer optimizer = new ImageOptimizer(2560, 0.80f, 40_000_000L, 2);

    @TempDir
    Path dir;

    @Test
    void convertsAPngToASmallerWebpAndLeavesTheOriginalAlone() throws IOException {
        Path png = write("shot.png", photoLike(800, 600, false), "png");
        byte[] original = Files.readAllBytes(png);

        Path webp = optimizer.toWebp(png, AttachmentType.PNG).orElseThrow();

        assertTrue(Files.size(webp) < Files.size(png), "the WebP must be smaller");
        assertEquals(png.getParent(), webp.getParent(), "written next to the original");
        assertArrayEquals(original, Files.readAllBytes(png), "the original is untouched");
        BufferedImage decoded = readWebp(webp);
        assertEquals(800, decoded.getWidth());
        assertEquals(600, decoded.getHeight());
    }

    @Test
    void convertsJpegAndWebpUploadsToo() throws IOException {
        Path jpeg = write("photo.jpg", photoLike(1200, 900, false), "jpeg");
        assertTrue(optimizer.toWebp(jpeg, AttachmentType.JPEG).isPresent());

        Path big = write("big.png", photoLike(1000, 800, false), "png");
        Path firstWebp = optimizer.toWebp(big, AttachmentType.PNG).orElseThrow();
        // Re-encoding an already compressed WebP at a lower size limit shrinks it again.
        ImageOptimizer smaller = new ImageOptimizer(300, 0.80f, 40_000_000L, 1);
        Path secondWebp = smaller.toWebp(firstWebp, AttachmentType.WEBP).orElseThrow();
        assertEquals(300, readWebp(secondWebp).getWidth());
    }

    @Test
    void scalesTheLongSideDownToTheLimitKeepingTheAspectRatio() throws IOException {
        ImageOptimizer limited = new ImageOptimizer(200, 0.80f, 40_000_000L, 1);
        Path wide = write("wide.png", photoLike(1600, 400, false), "png");
        Path tall = write("tall.png", photoLike(300, 1200, false), "png");

        BufferedImage wideResult = readWebp(limited.toWebp(wide, AttachmentType.PNG).orElseThrow());
        BufferedImage tallResult = readWebp(limited.toWebp(tall, AttachmentType.PNG).orElseThrow());

        assertEquals(200, wideResult.getWidth());
        assertEquals(50, wideResult.getHeight());
        assertEquals(50, tallResult.getWidth());
        assertEquals(200, tallResult.getHeight());
    }

    @Test
    void keepsTransparency() throws IOException {
        Path png = write("logo.png", photoLike(600, 400, true), "png");

        BufferedImage decoded = readWebp(optimizer.toWebp(png, AttachmentType.PNG).orElseThrow());

        assertTrue(decoded.getColorModel().hasAlpha());
        assertEquals(0, decoded.getRGB(5, 5) >>> 24, "the transparent corner stays transparent");
    }

    @Test
    void turnsAPhotoUprightFromItsExifOrientation() throws IOException {
        // Left half red, right half blue; orientation 6 means "turn 90° clockwise to display",
        // so the left side ends up on top.
        BufferedImage halves = new BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = halves.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, 200, 200);
        g.setColor(Color.BLUE);
        g.fillRect(200, 0, 200, 200);
        g.dispose();
        Path jpeg = dir.resolve("phone.jpg");
        Files.write(jpeg, withExifOrientation(encode(halves, "jpeg"), 6));

        BufferedImage upright = readWebp(optimizer.toWebp(jpeg, AttachmentType.JPEG).orElseThrow());

        assertEquals(200, upright.getWidth());
        assertEquals(400, upright.getHeight());
        Color top = new Color(upright.getRGB(100, 50));
        Color bottom = new Color(upright.getRGB(100, 350));
        assertTrue(top.getRed() > 200 && top.getBlue() < 60, "red on top, was " + top);
        assertTrue(bottom.getBlue() > 200 && bottom.getRed() < 60, "blue at the bottom, was " + bottom);
    }

    @Test
    void everyOrientationGivesTheRightSize() {
        BufferedImage image = new BufferedImage(40, 10, BufferedImage.TYPE_INT_RGB);
        for (int orientation = 1; orientation <= 8; orientation++) {
            BufferedImage turned = ImageOptimizer.applyOrientation(image, orientation);
            boolean swapped = orientation >= 5;
            assertEquals(swapped ? 10 : 40, turned.getWidth(), "orientation " + orientation);
            assertEquals(swapped ? 40 : 10, turned.getHeight(), "orientation " + orientation);
        }
    }

    @Test
    void leavesGifsAndNonImagesAlone() throws IOException {
        Path gif = write("anim.gif", photoLike(300, 300, false), "gif");

        assertEquals(Optional.empty(), optimizer.toWebp(gif, AttachmentType.GIF));
        assertEquals(Optional.empty(), optimizer.toWebp(gif, AttachmentType.PDF));
        assertEquals(List.of(gif), filesInDir());
    }

    @Test
    void keepsTheOriginalOfAnImageOverThePixelLimitWithoutDecodingIt() throws IOException {
        ImageOptimizer strict = new ImageOptimizer(2560, 0.80f, 10_000L, 1);
        Path png = write("huge.png", photoLike(200, 100, false), "png");

        assertEquals(Optional.empty(), strict.toWebp(png, AttachmentType.PNG));
        assertEquals(List.of(png), filesInDir());
    }

    @Test
    void keepsTheOriginalOfABrokenImageWithoutFailing() throws IOException {
        Path broken = dir.resolve("broken.png");
        Files.write(broken, new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 1, 2, 3, 4});

        assertEquals(Optional.empty(), optimizer.toWebp(broken, AttachmentType.PNG));
        assertEquals(List.of(broken), filesInDir(), "no temporary file is left behind");
    }

    // --- helpers ---

    /** Smooth gradients with a little noise, like a photo; optionally a transparent top-left corner. */
    private static BufferedImage photoLike(int width, int height, boolean alpha) {
        BufferedImage image = new BufferedImage(width, height,
                alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Random random = new Random(42);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int r = (x * 255 / width + random.nextInt(24)) & 0xFF;
                int g = (y * 255 / height + random.nextInt(24)) & 0xFF;
                int b = ((x + y) * 255 / (width + height) + random.nextInt(24)) & 0xFF;
                int a = alpha && x < width / 4 && y < height / 4 ? 0 : 0xFF;
                image.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
            }
        }
        return image;
    }

    private Path write(String name, BufferedImage image, String format) throws IOException {
        Path file = dir.resolve(name);
        Files.write(file, encode(image, format));
        return file;
    }

    private static byte[] encode(BufferedImage image, String format) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, format, out), "no writer for " + format);
        return out.toByteArray();
    }

    private static BufferedImage readWebp(Path file) throws IOException {
        ImageReader reader = new WebPImageReaderSpi().createReaderInstance(null);
        try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
            reader.setInput(in);
            return reader.read(0);
        } finally {
            reader.dispose();
        }
    }

    private List<Path> filesInDir() throws IOException {
        try (var files = Files.list(dir)) {
            return files.toList();
        }
    }

    /** Inserts an APP1 Exif segment holding only the orientation tag, right after the JPEG's SOI. */
    private static byte[] withExifOrientation(byte[] jpeg, int orientation) {
        byte[] tiff = {
                'M', 'M', 0, 42, 0, 0, 0, 8,          // big-endian TIFF header, first IFD at 8
                0, 1,                                  // one entry
                0x01, 0x12, 0, 3, 0, 0, 0, 1,          // tag 0x0112 (orientation), SHORT, count 1
                0, (byte) orientation, 0, 0,           // the value, padded
                0, 0, 0, 0};                           // no next IFD
        byte[] exifHeader = {'E', 'x', 'i', 'f', 0, 0};
        int length = 2 + exifHeader.length + tiff.length;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);                         // SOI
        out.write(0xFF);
        out.write(0xE1);
        out.write(length >> 8);
        out.write(length & 0xFF);
        out.write(exifHeader, 0, exifHeader.length);
        out.write(tiff, 0, tiff.length);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }
}