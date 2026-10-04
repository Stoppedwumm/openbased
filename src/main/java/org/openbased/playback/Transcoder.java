package org.openbased.playback;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.openbased.common.ApiException;
import org.openbased.config.OpenBasedProperties;
import org.openbased.media.MediaFile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Runs ffmpeg to remux or transcode a file into a streamable fragmented MP4 or WebM. */
@Component
public class Transcoder {

    private final OpenBasedProperties properties;

    public Transcoder(OpenBasedProperties properties) {
        this.properties = properties;
    }

    public String contentType(PlaybackPlan plan, MediaFile file) {
        boolean audioOnly = file.getVideoCodec() == null && file.getWidth() == null;
        return switch (plan.container()) {
            case "webm" -> audioOnly ? "audio/webm" : "video/webm";
            default -> audioOnly ? "audio/mp4" : "video/mp4";
        };
    }

    /**
     * Starts ffmpeg and returns its output. Closing the stream (for example when the client disconnects)
     * terminates the process.
     *
     * @param startSeconds position to start from
     */
    public InputStream start(PlaybackSession session, MediaFile file, double startSeconds) {
        PlaybackPlan plan = session.plan();
        List<String> command = new ArrayList<>(List.of(properties.getPlayback().getFfmpeg(), "-nostdin", "-v", "error"));
        if (startSeconds > 0) {
            command.addAll(List.of("-ss", String.format(Locale.ROOT, "%.3f", startSeconds)));
        }
        command.addAll(List.of("-i", file.getPath(), "-map", "0:v:0?", "-map", "0:a:0?", "-sn", "-dn"));
        if (plan.mode() == PlaybackMode.REMUX) {
            command.addAll(List.of("-c", "copy"));
        } else {
            if (plan.videoCodec() == null) {
                command.add("-vn");
            } else if ("vp9".equals(plan.videoCodec())) {
                command.addAll(List.of("-c:v", "libvpx-vp9", "-deadline", "realtime", "-cpu-used", "8", "-row-mt", "1",
                        "-b:v", "0", "-crf", "33"));
            } else {
                command.addAll(List.of("-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-pix_fmt", "yuv420p"));
            }
            if ("opus".equals(plan.audioCodec())) {
                command.addAll(List.of("-c:a", "libopus", "-b:a", "160k", "-ac", "2"));
            } else {
                command.addAll(List.of("-c:a", "aac", "-b:a", "192k", "-ac", "2"));
            }
        }
        if ("webm".equals(plan.container())) {
            command.addAll(List.of("-f", "webm"));
        } else {
            command.addAll(List.of("-movflags", "frag_keyframe+empty_moov+default_base_moof", "-f", "mp4"));
        }
        command.add("pipe:1");
        try {
            Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            process.getOutputStream().close();
            session.track(process);
            return new FilterInputStream(process.getInputStream()) {
                @Override
                public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        process.destroyForcibly();
                    }
                }
            };
        } catch (IOException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "TRANSCODER_UNAVAILABLE",
                    "ffmpeg is not available on this server.");
        }
    }
}
