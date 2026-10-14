package org.openbased.media;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

import org.openbased.config.OpenBasedProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scales artwork down to a few fixed widths and caches the results under {@code data-dir/artwork-cache}. Requested
 * widths are rounded up to the next fixed width, so the cache holds at most {@link #WIDTHS} copies per image.
 * Cached files are keyed by the source's size and modification time, so a replaced image is never served stale.
 */
@Component
public class ArtworkResizer {

    /** The widths served; a request is rounded up to the next one. Wider requests get the original. */
    public static final int[] WIDTHS = { 92, 154, 185, 342, 500, 780, 1280 };

    private static final Logger log = LoggerFactory.getLogger(ArtworkResizer.class);

    private final OpenBasedProperties properties;
    private final ArtworkRepository artwork;

    public ArtworkResizer(OpenBasedProperties properties, ArtworkRepository artwork) {
        this.properties = properties;
        this.artwork = artwork;
    }

    /** A scaled image and its content type. */
    public record Scaled(Path path, String contentType) {
    }

    /** The fixed width a request for {@code width} is served at, or empty for the original. */
    static Optional<Integer> snap(int width) {
        return Arrays.stream(WIDTHS).filter(w -> w >= width).boxed().findFirst();
    }

    /**
     * The image scaled to the fixed width at or above {@code width}, or empty when the original should be served:
     * it is not wider than that, the request is wider than every fixed width, or it cannot be decoded (ImageIO reads
     * JPEG, PNG, GIF and BMP).
     */
    public Optional<Scaled> scaled(Artwork art, Path source, int width) throws IOException {
        Optional<Integer> snapped = snap(width);
        if (snapped.isEmpty()) {
            return Optional.empty();
        }
        int target = snapped.get();
        String version = version(source);
        Path dir = cacheDir();
        for (String ext : List.of("jpg", "png")) {
            Path cached = dir.resolve(art.getId() + "-" + target + "-" + version + "." + ext);
            if (Files.exists(cached)) {
                return Optional.of(new Scaled(cached, contentType(ext)));
            }
        }
        Path original = dir.resolve(art.getId() + "-" + target + "-" + version + ".original");
        if (Files.exists(original)) {
            // Known not to need or allow scaling.
            return Optional.empty();
        }

        BufferedImage image;
        try {
            image = ImageIO.read(source.toFile());
        } catch (IOException | RuntimeException e) {
            log.debug("Cannot decode artwork {}", art.getId(), e);
            image = null;
        }
        Files.createDirectories(dir);
        if (image == null || image.getWidth() <= target) {
            touch(original);
            return Optional.empty();
        }
        boolean alpha = image.getColorModel().hasAlpha();
        String ext = alpha ? "png" : "jpg";
        Path cached = dir.resolve(art.getId() + "-" + target + "-" + version + "." + ext);
        Path temp = Files.createTempFile(dir, art.getId() + "-", ".tmp");
        try {
            BufferedImage scaled = scale(image, target, alpha);
            try (OutputStream out = Files.newOutputStream(temp)) {
                if (alpha) {
                    ImageIO.write(scaled, "png", out);
                } else {
                    writeJpeg(scaled, out);
                }
            }
            Files.move(temp, cached, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
        return Optional.of(new Scaled(cached, contentType(ext)));
    }

    /** Halves the size in steps, then scales to the target, which keeps large reductions sharp without aliasing. */
    static BufferedImage scale(BufferedImage image, int width, boolean alpha) {
        int type = alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        int targetHeight = Math.max(1, (int) Math.round((double) image.getHeight() * width / image.getWidth()));
        BufferedImage current = image;
        int w = image.getWidth();
        int h = image.getHeight();
        do {
            w = Math.max(width, w / 2);
            h = w == width ? targetHeight : Math.max(targetHeight, h / 2);
            BufferedImage next = new BufferedImage(w, h, type);
            Graphics2D g = next.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(current, 0, 0, w, h, null);
            g.dispose();
            current = next;
        } while (w > width);
        return current;
    }

    private static void writeJpeg(BufferedImage image, OutputStream out) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(0.85f);
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
    }

    private static void touch(Path path) throws IOException {
        try {
            Files.createFile(path);
        } catch (java.nio.file.FileAlreadyExistsException ignored) {
            // Another request got here first.
        }
    }

    private static String contentType(String ext) {
        return "png".equals(ext) ? "image/png" : "image/jpeg";
    }

    /** Deletes cached copies of artwork that no longer exists, and of older versions of an image. */
    @Scheduled(fixedDelay = 86_400_000, initialDelay = 600_000)
    void prune() {
        Path dir = cacheDir();
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (name.endsWith(".tmp")) {
                    // Being written, or left by a crash; remove only when old.
                    if (Files.getLastModifiedTime(entry).toInstant()
                            .isBefore(java.time.Instant.now().minusSeconds(3600))) {
                        Files.deleteIfExists(entry);
                    }
                    continue;
                }
                int dash = name.indexOf('-');
                String id = dash > 0 ? name.substring(0, dash) : name;
                Optional<Artwork> art = artwork.findById(id);
                boolean stale = art.isEmpty() || !Files.exists(Path.of(art.get().getPath()))
                        || !name.contains("-" + version(Path.of(art.get().getPath())) + ".");
                if (stale) {
                    Files.deleteIfExists(entry);
                }
            }
        } catch (IOException e) {
            log.warn("Could not prune the artwork cache", e);
        }
    }

    private static String version(Path source) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(source, BasicFileAttributes.class);
        return Long.toHexString(attributes.size()) + "-" + Long.toHexString(attributes.lastModifiedTime().toMillis());
    }

    private Path cacheDir() {
        return properties.getDataDir().resolve("artwork-cache");
    }
}
