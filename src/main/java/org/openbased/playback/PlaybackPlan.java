package org.openbased.playback;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.openbased.common.ApiException;
import org.openbased.media.MediaFile;

/**
 * How a file will be delivered to a client.
 *
 * @param container output container for REMUX/TRANSCODE ({@code mp4} or {@code webm})
 * @param videoCodec output video codec for TRANSCODE, or {@code null} to copy / for audio-only files
 * @param audioCodec output audio codec for TRANSCODE, or {@code null} to copy
 */
public record PlaybackPlan(PlaybackMode mode, String container, String videoCodec, String audioCodec) {

    private static final Set<String> MP4_VIDEO = Set.of("h264", "hevc", "av1", "mpeg4");
    private static final Set<String> MP4_AUDIO = Set.of("aac", "mp3", "opus", "ac3", "eac3", "flac", "alac");
    private static final Set<String> WEBM_VIDEO = Set.of("vp8", "vp9", "av1");
    private static final Set<String> WEBM_AUDIO = Set.of("opus", "vorbis");

    /** What the client can play. Empty sets mean "not specified". */
    public record Capabilities(Set<String> containers, Set<String> videoCodecs, Set<String> audioCodecs) {

        public static Capabilities normalize(Set<String> containers, Set<String> videoCodecs, Set<String> audioCodecs) {
            return new Capabilities(norm(containers, PlaybackPlan::container), norm(videoCodecs, PlaybackPlan::codec),
                    norm(audioCodecs, PlaybackPlan::codec));
        }

        private static Set<String> norm(Set<String> values, java.util.function.UnaryOperator<String> f) {
            return values == null ? Set.of() : values.stream().filter(v -> v != null && !v.isBlank()).map(f)
                    .collect(Collectors.toUnmodifiableSet());
        }

        boolean isEmpty() {
            return containers.isEmpty() && videoCodecs.isEmpty() && audioCodecs.isEmpty();
        }
    }

    public static PlaybackPlan decide(MediaFile file, Capabilities caps) {
        if (caps == null || caps.isEmpty()) {
            return new PlaybackPlan(PlaybackMode.DIRECT_PLAY, null, null, null);
        }
        String container = container(file.getContainer());
        String video = file.getVideoCodec() == null ? null : codec(file.getVideoCodec());
        String audio = file.getAudioCodec() == null ? null : codec(file.getAudioCodec());
        boolean videoOk = video == null || caps.videoCodecs().isEmpty() || caps.videoCodecs().contains(video);
        boolean audioOk = audio == null || caps.audioCodecs().isEmpty() || caps.audioCodecs().contains(audio);
        boolean containerOk = caps.containers().isEmpty() || caps.containers().contains(container);

        if (containerOk && videoOk && audioOk) {
            return new PlaybackPlan(PlaybackMode.DIRECT_PLAY, null, null, null);
        }
        if (videoOk && audioOk) {
            if (caps.containers().contains("mp4") && fits(video, MP4_VIDEO) && fits(audio, MP4_AUDIO)) {
                return new PlaybackPlan(PlaybackMode.REMUX, "mp4", null, null);
            }
            if (caps.containers().contains("webm") && fits(video, WEBM_VIDEO) && fits(audio, WEBM_AUDIO)) {
                return new PlaybackPlan(PlaybackMode.REMUX, "webm", null, null);
            }
        }
        boolean mp4 = caps.containers().isEmpty() || caps.containers().contains("mp4");
        if (mp4 && accepts(caps.videoCodecs(), "h264", video == null) && accepts(caps.audioCodecs(), "aac", false)) {
            return new PlaybackPlan(PlaybackMode.TRANSCODE, "mp4", video == null ? null : "h264", "aac");
        }
        if (caps.containers().contains("webm") && accepts(caps.videoCodecs(), "vp9", video == null)
                && accepts(caps.audioCodecs(), "opus", false)) {
            return new PlaybackPlan(PlaybackMode.TRANSCODE, "webm", video == null ? null : "vp9", "opus");
        }
        throw ApiException.unprocessable("UNSUPPORTED_CAPABILITIES",
                "The client supports none of the formats this server can produce (mp4 with h264/aac or webm with vp9/opus).");
    }

    private static boolean fits(String codec, Set<String> allowed) {
        return codec == null || allowed.contains(codec);
    }

    private static boolean accepts(Set<String> supported, String codec, boolean notNeeded) {
        return notNeeded || supported.isEmpty() || supported.contains(codec);
    }

    static String container(String c) {
        String v = c == null ? "" : c.toLowerCase(Locale.ROOT);
        return switch (v) {
            case "m4v", "mov", "m4a", "mpeg4" -> "mp4";
            case "matroska" -> "mkv";
            default -> v;
        };
    }

    static String codec(String c) {
        String v = c.toLowerCase(Locale.ROOT);
        return switch (v) {
            case "avc", "avc1", "h.264", "x264" -> "h264";
            case "h265", "h.265", "hev1", "hvc1", "x265" -> "hevc";
            case "av01" -> "av1";
            case "mp4a" -> "aac";
            case "ec-3" -> "eac3";
            case "ac-3" -> "ac3";
            default -> v;
        };
    }
}
