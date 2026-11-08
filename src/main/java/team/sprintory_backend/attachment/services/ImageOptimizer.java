package team.sprintory_backend.attachment.services;

import com.drew.imaging.jpeg.JpegMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.luciad.imageio.webp.CompressionType;
import com.luciad.imageio.webp.WebPImageReaderSpi;
import com.luciad.imageio.webp.WebPImageWriterSpi;
import com.luciad.imageio.webp.WebPWriteParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import team.sprintory_backend.attachment.models.AttachmentType;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Semaphore;

/**
 * Compresses uploaded PNG, JPEG and WebP images to lossy WebP (docs/attachments-docs.md §3.2).
 *
 * <p>The original is never changed: {@link #toWebp} writes a new file next to it and returns it
 * only when it is smaller. In every other case (a GIF, an image over the pixel limit, one that
 * cannot be decoded, a WebP that is not smaller) it returns empty and the caller stores the
 * original. Decoding a large image needs a lot of memory (4 bytes per pixel), so the pixel count
 * is read from the header before decoding, and only a few conversions run at once.
 *
 * <p>The WebP reader and writer are created from their service providers directly rather than
 * looked up through {@code ImageIO}'s registry, which does not always see plugins inside a
 * Spring Boot jar.
 */
@Component
public class ImageOptimizer {

    private static final Logger log = LoggerFactory.getLogger(ImageOptimizer.class);

    private static final Set<AttachmentType> CONVERTED = Set.of(
            AttachmentType.PNG, AttachmentType.JPEG, AttachmentType.WEBP);

    private final int maxDimension;
    private final float quality;
    private final long maxPixels;
    private final Semaphore conversions;

    public ImageOptimizer(@Value("${app.attachments.images.max-dimension:2560}") int maxDimension,
                          @Value("${app.attachments.images.quality:0.80}") float quality,
                          @Value("${app.attachments.images.max-pixels:40000000}") long maxPixels,
                          @Value("${app.attachments.images.max-concurrent:2}") int maxConcurrent) {
        this.maxDimension = maxDimension;
        this.quality = quality;
        this.maxPixels = maxPixels;
        this.conversions = new Semaphore(maxConcurrent);
    }

    /**
     * A smaller WebP copy of the image at {@code source}, in the same directory, or empty when
     * the original should be stored as it is. Never throws for a bad image.
     */
    public Optional<Path> toWebp(Path source, AttachmentType type) {
        if (!CONVERTED.contains(type)) {
            return Optional.empty();
        }
        try {
            conversions.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
        Path target = null;
        try {
            BufferedImage image = decode(source, type);
            if (image == null) {
                return Optional.empty();
            }
            // Shrink first: the long side is the same either way, and turning a smaller image is cheaper.
            image = downscale(image);
            if (type == AttachmentType.JPEG) {
                image = applyOrientation(image, orientation(source));
            }
            image = encodable(image);

            target = Files.createTempFile(source.getParent(), "webp-", ".tmp");
            writeWebp(image, target);
            if (Files.size(target) >= Files.size(source)) {
                Files.deleteIfExists(target);
                return Optional.empty();
            }
            return Optional.of(target);
        } catch (IOException | RuntimeException | LinkageError e) {
            // A broken image, an unsupported variant (e.g. CMYK JPEG) or a missing native library:
            // the original is stored instead, so the upload still succeeds.
            log.warn("Image not converted to WebP, keeping the original: {}", e.toString());
            deleteQuietly(target);
            return Optional.empty();
        } finally {
            conversions.release();
        }
    }

    // --- decoding ---

    /** Null when the header cannot be read or the image has more pixels than allowed. */
    private BufferedImage decode(Path source, AttachmentType type) throws IOException {
        ImageReader reader = reader(type);
        try (ImageInputStream in = ImageIO.createImageInputStream(source.toFile())) {
            if (in == null) {
                return null;
            }
            reader.setInput(in, true, true);
            long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
            if (pixels > maxPixels) {
                log.info("Image of {} pixels is over the limit of {}; stored without conversion.", pixels, maxPixels);
                return null;
            }
            return reader.read(0);
        } finally {
            reader.dispose();
        }
    }

    private static ImageReader reader(AttachmentType type) throws IOException {
        if (type == AttachmentType.WEBP) {
            return new WebPImageReaderSpi().createReaderInstance(null);
        }
        Iterator<ImageReader> readers = ImageIO.getImageReadersByMIMEType(type.mimeType());
        if (!readers.hasNext()) {
            throw new IOException("No image reader for " + type.mimeType());
        }
        return readers.next();
    }

    // --- orientation ---

    /** The EXIF orientation (1–8) of a JPEG; 1 (as stored) when there is none or it cannot be read. */
    private static int orientation(Path jpeg) {
        try {
            Metadata metadata = JpegMetadataReader.readMetadata(jpeg.toFile());
            ExifIFD0Directory exif = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (exif != null && exif.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                return exif.getInt(ExifIFD0Directory.TAG_ORIENTATION);
            }
        } catch (Exception e) {
            log.debug("No readable EXIF orientation: {}", e.toString());
        }
        return 1;
    }

    /** Turns the stored pixels the way the camera asked, so the result needs no EXIF to look right. */
    static BufferedImage applyOrientation(BufferedImage image, int orientation) {
        if (orientation < 2 || orientation > 8) {
            return image;
        }
        int w = image.getWidth();
        int h = image.getHeight();
        boolean swaps = orientation >= 5;
        AffineTransform t = new AffineTransform();
        switch (orientation) {
            case 2 -> { t.scale(-1, 1); t.translate(-w, 0); }             // mirrored
            case 3 -> { t.translate(w, h); t.rotate(Math.PI); }           // upside down
            case 4 -> { t.scale(1, -1); t.translate(0, -h); }             // mirrored vertically
            case 5 -> { t.rotate(-Math.PI / 2); t.scale(-1, 1); }         // transposed
            case 6 -> { t.translate(h, 0); t.rotate(Math.PI / 2); }       // turn 90° clockwise
            case 7 -> { t.scale(-1, 1); t.translate(-h, w); t.rotate(3 * Math.PI / 2); } // transversed
            case 8 -> { t.translate(0, w); t.rotate(3 * Math.PI / 2); }   // turn 90° anticlockwise
            default -> { return image; }
        }
        BufferedImage turned = new BufferedImage(swaps ? h : w, swaps ? w : h, typeFor(image));
        Graphics2D g = turned.createGraphics();
        try {
            g.drawImage(image, t, null);
        } finally {
            g.dispose();
        }
        return turned;
    }

    // --- resizing ---

    /** At most {@code maxDimension} on the long side, halving first so big reductions stay smooth. */
    private BufferedImage downscale(BufferedImage image) {
        int longSide = Math.max(image.getWidth(), image.getHeight());
        if (longSide <= maxDimension) {
            return image;
        }
        double scale = (double) maxDimension / longSide;
        int targetW = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int targetH = Math.max(1, (int) Math.round(image.getHeight() * scale));

        BufferedImage current = image;
        while (current.getWidth() / 2 >= targetW && current.getHeight() / 2 >= targetH) {
            current = resize(current, current.getWidth() / 2, current.getHeight() / 2);
        }
        return resize(current, targetW, targetH);
    }

    private static BufferedImage resize(BufferedImage image, int width, int height) {
        BufferedImage resized = new BufferedImage(width, height, typeFor(image));
        Graphics2D g = resized.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(image, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        return resized;
    }

    // --- encoding ---

    /** Plain RGB or ARGB pixels, which the encoder takes; palette and grey images are converted. */
    private static BufferedImage encodable(BufferedImage image) {
        int type = typeFor(image);
        if (image.getType() == type) {
            return image;
        }
        BufferedImage converted = new BufferedImage(image.getWidth(), image.getHeight(), type);
        Graphics2D g = converted.createGraphics();
        try {
            g.drawImage(image, 0, 0, null);
        } finally {
            g.dispose();
        }
        return converted;
    }

    private static int typeFor(BufferedImage image) {
        return image.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
    }

    private void writeWebp(BufferedImage image, Path target) throws IOException {
        ImageWriter writer = new WebPImageWriterSpi().createWriterInstance(null);
        try (ImageOutputStream out = ImageIO.createImageOutputStream(target.toFile())) {
            WebPWriteParam param = new WebPWriteParam(Locale.ROOT);
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionType(CompressionType.Lossy);
            param.setCompressionQuality(quality);
            writer.setOutput(out);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Could not delete temporary file {}: {}", file, e.toString());
        }
    }
}