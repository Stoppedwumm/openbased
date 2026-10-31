package org.openbased.playback;

import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;

import org.openbased.common.ApiException;
import org.openbased.config.OpenBasedProperties;
import org.openbased.media.MediaFile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Runs ffmpeg to remux or transcode a file, either into a streamable fragmented MP4 or WebM, or into a regular
 * file for downloads.
 */
@Component
public class Transcoder {

    /**
     * Limits for re-encoded video. {@code null} fields mean no limit.
     *
     * @param maxHeight output height in pixels; taller video is scaled down, keeping the aspect ratio
     * @param maxVideoBitrate peak video bitrate in bits per second
     */
    public record Limits(Integer maxHeight, Long maxVideoBitrate) {
        public static final Limits NONE = new Limits(null, null);
    }

    /** Reports progress of a file transcode and asks whether to stop. */
    public interface Progress {
        void percent(int percent);

        boolean cancelled();
    }

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
        command.addAll(List.of("-i", file.getPath()));
        command.addAll(encodeArguments(plan, Limits.NONE));
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

    /** ffmpeg arguments after the input: stream selection and codecs. */
    static List<String> encodeArguments(PlaybackPlan plan, Limits limits) {
        List<String> args = new ArrayList<>(List.of("-map", "0:v:0?", "-map", "0:a:0?", "-sn", "-dn"));
        if (plan.mode() == PlaybackMode.REMUX) {
            args.addAll(List.of("-c", "copy"));
            return args;
        }
        if (plan.videoCodec() == null) {
            args.add("-vn");
        } else {
            if (limits.maxHeight() != null) {
                // Never upscale; -2 keeps the width even, as both encoders require.
                args.addAll(List.of("-vf", "scale=-2:'min(ih," + limits.maxHeight() + ")'"));
            }
            if ("vp9".equals(plan.videoCodec())) {
                args.addAll(List.of("-c:v", "libvpx-vp9", "-deadline", "realtime", "-cpu-used", "8", "-row-mt", "1",
                        "-crf", "33"));
                // Constrained quality: with -b:v set, VP9 uses it as the maximum.
                args.addAll(List.of("-b:v",
                        limits.maxVideoBitrate() == null ? "0" : limits.maxVideoBitrate().toString()));
            } else {
                args.addAll(List.of("-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-pix_fmt", "yuv420p"));
                if (limits.maxVideoBitrate() != null) {
                    args.addAll(List.of("-maxrate", limits.maxVideoBitrate().toString(),
                            "-bufsize", Long.toString(limits.maxVideoBitrate() * 2)));
                }
            }
        }
        if ("opus".equals(plan.audioCodec())) {
            args.addAll(List.of("-c:a", "libopus", "-b:a", "160k", "-ac", "2"));
        } else {
            args.addAll(List.of("-c:a", "aac", "-b:a", "192k", "-ac", "2"));
        }
        return args;
    }

    /** The ffmpeg command that writes {@code file} to {@code output} according to the plan. */
    List<String> fileCommand(MediaFile file, PlaybackPlan plan, Limits limits, Path output) {
        List<String> command = new ArrayList<>(List.of(properties.getPlayback().getFfmpeg(), "-nostdin", "-v", "error",
                "-y", "-i", file.getPath()));
        command.addAll(encodeArguments(plan, limits));
        if ("webm".equals(plan.container())) {
            command.addAll(List.of("-f", "webm"));
        } else {
            // A regular MP4 with the index at the start, so players can start before the end has been read.
            command.addAll(List.of("-movflags", "+faststart", "-f", "mp4"));
        }
        command.addAll(List.of("-progress", "pipe:1", "-nostats", output.toString()));
        return command;
    }

    /**
     * Writes the file to {@code output}, reporting progress from ffmpeg's {@code out_time_us}. Blocks until
     * ffmpeg has finished; deletes the output and throws if it fails or is cancelled.
     */
    public void toFile(MediaFile file, PlaybackPlan plan, Limits limits, Path output, Progress progress)
            throws IOException, InterruptedException {
        Process process;
        try {
            process = new ProcessBuilder(fileCommand(file, plan, limits, output))
                    .redirectError(ProcessBuilder.Redirect.PIPE).start();
        } catch (IOException e) {
            throw new IOException("ffmpeg is not available on this server.", e);
        }
        process.getOutputStream().close();
        // Keep the last lines of stderr for the error message; drain it so ffmpeg never blocks on it.
        StringBuffer errors = new StringBuffer();
        Thread stderr = new Thread(() -> {
            try (BufferedReader reader = process.errorReader()) {
                String line;
                while ((line = reader.readLine()) != null) {
                    errors.append(line).append('\n');
                    if (errors.length() > 4000) {
                        errors.delete(0, errors.length() - 2000);
                    }
                }
            } catch (IOException ignored) {
                // The process ended.
            }
        }, "ffmpeg-stderr");
        stderr.setDaemon(true);
        stderr.start();
        long durationMicros = file.getDuration() == null ? 0 : file.getDuration() * 1000;
        try (BufferedReader reader = process.inputReader()) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (progress.cancelled()) {
                    break;
                }
                if (line.startsWith("out_time_us=") && durationMicros > 0) {
                    try {
                        long out = Long.parseLong(line.substring("out_time_us=".length()).trim());
                        progress.percent((int) Math.min(99, out * 100 / durationMicros));
                    } catch (NumberFormatException ignored) {
                        // "N/A" before the first frame.
                    }
                }
            }
        } finally {
            if (progress.cancelled()) {
                process.destroyForcibly();
            }
        }
        int exit = process.waitFor();
        stderr.join(1000);
        if (progress.cancelled() || exit != 0) {
            Files.deleteIfExists(output);
            if (progress.cancelled()) {
                throw new CancellationException("Cancelled");
            }
            String message = errors.toString().trim();
            throw new IOException("ffmpeg failed (exit code " + exit + ")" + (message.isEmpty() ? "" : ": "
                    + message.substring(Math.max(0, message.length() - 500))));
        }
    }
}
