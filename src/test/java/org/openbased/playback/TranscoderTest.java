package org.openbased.playback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openbased.config.OpenBasedProperties;
import org.openbased.media.MediaFile;

class TranscoderTest {

    private static final PlaybackPlan H264 = new PlaybackPlan(PlaybackMode.TRANSCODE, "mp4", "h264", "aac");

    @TempDir
    Path tmp;

    @Test
    void limitsScaleDownAndCapTheBitrate() {
        List<String> args = Transcoder.encodeArguments(H264, new Transcoder.Limits(720, 2_000_000L));
        assertThat(String.join(" ", args)).contains("-vf scale=-2:'min(ih,720)'")
                .contains("-maxrate 2000000 -bufsize 4000000");
        List<String> vp9 = Transcoder.encodeArguments(new PlaybackPlan(PlaybackMode.TRANSCODE, "webm", "vp9", "opus"),
                new Transcoder.Limits(null, 1_500_000L));
        assertThat(String.join(" ", vp9)).contains("-crf 33 -b:v 1500000").doesNotContain("scale");
    }

    @Test
    void remuxIgnoresLimits() {
        assertThat(Transcoder.encodeArguments(new PlaybackPlan(PlaybackMode.REMUX, "mp4", null, null),
                new Transcoder.Limits(480, 1_000_000L))).contains("copy").doesNotContain("-vf", "-maxrate");
    }

    @Test
    void writesAScaledFileAndReportsProgress() throws Exception {
        assumeTrue(available("ffmpeg") && available("ffprobe"), "ffmpeg is not installed");
        MediaFile source = source();
        Path out = tmp.resolve("out.part");
        int[] last = { -1 };
        new Transcoder(new OpenBasedProperties()).toFile(source, H264, new Transcoder.Limits(144, 300_000L), out,
                progress(() -> false, p -> last[0] = p));
        assertThat(last[0]).isBetween(1, 99);
        Process probe = new ProcessBuilder("ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
                "stream=height,codec_name", "-of", "csv=p=0", out.toString()).start();
        String result = new String(probe.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertThat(result).isEqualTo("h264,144");
    }

    @Test
    void cancellingDeletesTheOutput() throws Exception {
        assumeTrue(available("ffmpeg"), "ffmpeg is not installed");
        Path out = tmp.resolve("cancelled.part");
        assertThatThrownBy(() -> new Transcoder(new OpenBasedProperties()).toFile(source(), H264,
                Transcoder.Limits.NONE, out, progress(() -> true, p -> { })))
                .isInstanceOf(CancellationException.class);
        assertThat(out).doesNotExist();
    }

    @Test
    void failuresIncludeFfmpegsMessage() throws Exception {
        assumeTrue(available("ffmpeg"), "ffmpeg is not installed");
        Path broken = Files.write(tmp.resolve("broken.mkv"), new byte[1000]);
        MediaFile file = new MediaFile();
        file.setPath(broken.toString());
        Path out = tmp.resolve("broken.part");
        assertThatThrownBy(() -> new Transcoder(new OpenBasedProperties()).toFile(file, H264, Transcoder.Limits.NONE,
                out, progress(() -> false, p -> { })))
                .isInstanceOf(IOException.class).hasMessageContaining("ffmpeg failed");
        assertThat(out).doesNotExist();
    }

    private MediaFile source() throws Exception {
        Path video = tmp.resolve("source.mkv");
        Process ffmpeg = new ProcessBuilder("ffmpeg", "-v", "error", "-f", "lavfi", "-i", "testsrc=size=640x480:rate=25",
                "-f", "lavfi", "-i", "sine", "-t", "3", "-c:v", "libx264", "-c:a", "aac", video.toString())
                .redirectErrorStream(true).start();
        assertThat(ffmpeg.waitFor()).isZero();
        MediaFile file = new MediaFile();
        file.setPath(video.toString());
        file.setDuration(3000L);
        return file;
    }

    private static Transcoder.Progress progress(java.util.function.BooleanSupplier cancelled,
            java.util.function.IntConsumer percent) {
        return new Transcoder.Progress() {
            @Override
            public void percent(int p) {
                percent.accept(p);
            }

            @Override
            public boolean cancelled() {
                return cancelled.getAsBoolean();
            }
        };
    }

    private static boolean available(String tool) {
        try {
            return new ProcessBuilder(tool, "-version").redirectErrorStream(true).start().waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }
}
