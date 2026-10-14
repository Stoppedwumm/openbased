package org.openbased.media;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openbased.common.ApiException;
import org.openbased.config.OpenBasedProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Lists a file's subtitle tracks and serves them as WebVTT. Tracks come from the streams ffprobe found in the file
 * and from {@code .srt}/{@code .vtt} files next to it named after it ({@code Movie.mkv} →
 * {@code Movie.srt}, {@code Movie.en.srt}, {@code Movie.de.forced.vtt}). Sidecars are listed on each request,
 * so new ones appear without a rescan. Embedded text tracks are extracted with ffmpeg and cached.
 */
@Service
public class SubtitleService {

    /** Embedded codecs ffmpeg can convert to WebVTT. Image-based ones (PGS, VobSub, DVB) cannot be. */
    static final Set<String> TEXT_CODECS = Set.of("subrip", "srt", "ass", "ssa", "webvtt", "mov_text", "text");

    private static final Set<String> SIDECAR_EXTENSIONS = Set.of("srt", "vtt");
    private static final Pattern LANGUAGE = Pattern.compile("[a-z]{2,3}(-[a-z]{2,4})?");
    private static final Pattern SRT_TIMING = Pattern.compile(
            "^(\\d+:\\d{2}:\\d{2}),(\\d{3})(\\s*-->\\s*)(\\d+:\\d{2}:\\d{2}),(\\d{3})(.*)$");
    private static final Logger log = LoggerFactory.getLogger(SubtitleService.class);

    private final OpenBasedProperties properties;
    private final MediaFileRepository files;

    public SubtitleService(OpenBasedProperties properties, MediaFileRepository files) {
        this.properties = properties;
        this.files = files;
    }

    public enum Source {
        EMBEDDED,
        SIDECAR
    }

    /**
     * @param text whether it can be served as WebVTT
     * @param format the embedded codec, or {@code srt}/{@code vtt} for sidecars
     */
    public record Track(String id, Source source, String language, String title, String format, boolean forced,
            boolean defaultTrack, boolean hearingImpaired, boolean text) {
    }

    public List<Track> tracks(MediaFile file) {
        List<Track> tracks = new ArrayList<>();
        for (EmbeddedSubtitle s : file.getSubtitles()) {
            tracks.add(new Track("e" + s.getStreamIndex(), Source.EMBEDDED, s.getLanguage(), s.getTitle(), s.getCodec(),
                    s.isForced(), s.isDefaultTrack(), s.isHearingImpaired(),
                    s.getCodec() != null && TEXT_CODECS.contains(s.getCodec())));
        }
        sidecars(Path.of(file.getPath())).forEach(p -> tracks.add(sidecar(Path.of(file.getPath()), p)));
        return tracks;
    }

    /** The track as WebVTT text. */
    public String webvtt(MediaFile file, String trackId) {
        Path video = Path.of(file.getPath());
        if (trackId.startsWith("s")) {
            Path sidecar = sidecars(video).stream().filter(p -> sidecarId(p).equals(trackId)).findFirst()
                    .orElseThrow(SubtitleService::notFound);
            try {
                String content = decode(Files.readAllBytes(sidecar));
                return sidecar.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".srt") ? srtToVtt(content)
                        : content;
            } catch (IOException e) {
                throw notFound();
            }
        }
        EmbeddedSubtitle stream = file.getSubtitles().stream()
                .filter(s -> ("e" + s.getStreamIndex()).equals(trackId)).findFirst()
                .orElseThrow(SubtitleService::notFound);
        if (stream.getCodec() == null || !TEXT_CODECS.contains(stream.getCodec())) {
            throw ApiException.unprocessable("SUBTITLE_NOT_TEXT", "This subtitle track is made of images ("
                    + stream.getCodec() + ") and cannot be converted to WebVTT.");
        }
        return extract(file, stream.getStreamIndex());
    }

    /** {@code Movie.mkv} → {@code Movie.srt}, {@code Movie.en.srt}, {@code Movie.en.forced.vtt}, … */
    static List<Path> sidecars(Path video) {
        Path dir = video.getParent();
        String base = stripExtension(video.getFileName().toString());
        List<Path> found = new ArrayList<>();
        if (dir == null) {
            return found;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                String ext = extension(name);
                if (SIDECAR_EXTENSIONS.contains(ext) && (name.startsWith(base + ".")) && Files.isRegularFile(entry)) {
                    found.add(entry);
                }
            }
        } catch (IOException e) {
            log.debug("Cannot list subtitles next to {}", video, e);
        }
        found.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return found;
    }

    /** Language and flags from the name between the video's name and the extension. */
    static Track sidecar(Path video, Path sidecar) {
        String base = stripExtension(video.getFileName().toString());
        String name = sidecar.getFileName().toString();
        String ext = extension(name);
        String middle = name.substring(base.length(), name.length() - ext.length() - 1);
        String language = null;
        boolean forced = false;
        boolean defaultTrack = false;
        boolean hearingImpaired = false;
        List<String> rest = new ArrayList<>();
        for (String token : middle.split("\\.")) {
            String t = token.toLowerCase(Locale.ROOT);
            switch (t) {
                case "" -> { }
                case "forced" -> forced = true;
                case "default" -> defaultTrack = true;
                case "sdh", "cc", "hi" -> hearingImpaired = true;
                default -> {
                    if (language == null && LANGUAGE.matcher(t).matches()) {
                        language = t;
                    } else {
                        rest.add(token);
                    }
                }
            }
        }
        return new Track(sidecarId(sidecar), Source.SIDECAR, language, rest.isEmpty() ? null : String.join(" ", rest),
                ext, forced, defaultTrack, hearingImpaired, true);
    }

    /** Stable while the sidecar keeps its name; never reveals it. */
    static String sidecarId(Path sidecar) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(sidecar.getFileName().toString().getBytes(StandardCharsets.UTF_8));
            return "s" + HexFormat.of().formatHex(hash, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** UTF-8 (with or without BOM), UTF-16 with BOM, or else Windows-1252, which most older SRT files use. */
    static String decode(byte[] bytes) {
        if (bytes.length >= 2 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xfe) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xff) == 0xfe && (bytes[1] & 0xff) == 0xff) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        }
        int start = bytes.length >= 3 && (bytes[0] & 0xff) == 0xef && (bytes[1] & 0xff) == 0xbb
                && (bytes[2] & 0xff) == 0xbf ? 3 : 0;
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, start, bytes.length - start)).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, start, bytes.length - start, Charset.forName("windows-1252"));
        }
    }

    /** SubRip to WebVTT: a header, and {@code ,} instead of {@code .} before the milliseconds in cue timings. */
    static String srtToVtt(String srt) {
        StringBuilder out = new StringBuilder("WEBVTT\n\n");
        for (String line : srt.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            Matcher m = SRT_TIMING.matcher(line.strip());
            out.append(m.matches() ? m.group(1) + "." + m.group(2) + m.group(3) + m.group(4) + "." + m.group(5)
                    + m.group(6) : line).append('\n');
        }
        return out.toString();
    }

    private String extract(MediaFile file, int streamIndex) {
        Path source = Path.of(file.getPath());
        Path dir = cacheDir();
        try {
            Path cached = dir.resolve(file.getId() + "-" + streamIndex + "-" + version(source) + ".vtt");
            if (Files.exists(cached)) {
                return Files.readString(cached);
            }
            Files.createDirectories(dir);
            Process process;
            try {
                process = new ProcessBuilder(properties.getPlayback().getFfmpeg(), "-nostdin", "-v", "error", "-i",
                        source.toString(), "-map", "0:" + streamIndex, "-f", "webvtt", "pipe:1")
                        .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            } catch (IOException e) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "TRANSCODER_UNAVAILABLE",
                        "ffmpeg is not available on this server.");
            }
            process.getOutputStream().close();
            byte[] output = process.getInputStream().readAllBytes();
            if (!process.waitFor(10, TimeUnit.MINUTES) || process.exitValue() != 0) {
                process.destroyForcibly();
                throw ApiException.unprocessable("SUBTITLE_CONVERSION_FAILED",
                        "The subtitle track could not be converted.");
            }
            Path temp = Files.createTempFile(dir, file.getId() + "-", ".tmp");
            try {
                Files.write(temp, output);
                Files.move(temp, cached, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temp);
            }
            return new String(output, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw ApiException.unprocessable("SUBTITLE_CONVERSION_FAILED", "The subtitle track could not be read.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApiException.unprocessable("SUBTITLE_CONVERSION_FAILED", "Interrupted.");
        }
    }

    /** Deletes extracted tracks of files that are gone or have changed. */
    @Scheduled(fixedDelay = 86_400_000, initialDelay = 900_000)
    void prune() {
        Path dir = cacheDir();
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (name.endsWith(".tmp")) {
                    if (Files.getLastModifiedTime(entry).toInstant()
                            .isBefore(java.time.Instant.now().minusSeconds(3600))) {
                        Files.deleteIfExists(entry);
                    }
                    continue;
                }
                int dash = name.indexOf('-');
                MediaFile file = dash > 0 ? files.findById(name.substring(0, dash)).orElse(null) : null;
                Path source = file == null ? null : Path.of(file.getPath());
                if (source == null || !Files.exists(source) || !name.endsWith("-" + version(source) + ".vtt")) {
                    Files.deleteIfExists(entry);
                }
            }
        } catch (IOException e) {
            log.warn("Could not prune the subtitle cache", e);
        }
    }

    private static String version(Path source) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(source, BasicFileAttributes.class);
        return Long.toHexString(attributes.size()) + "-" + Long.toHexString(attributes.lastModifiedTime().toMillis());
    }

    private Path cacheDir() {
        return properties.getDataDir().resolve("subtitle-cache");
    }

    private static ApiException notFound() {
        return ApiException.notFound("SUBTITLE_NOT_FOUND", "The media file has no such subtitle track.");
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }
}
