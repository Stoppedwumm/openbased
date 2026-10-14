package org.openbased.download;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.openbased.media.MediaFile;
import org.openbased.playback.PlaybackMode;
import org.openbased.playback.PlaybackPlan;
import org.openbased.playback.Transcoder;

class DownloadPlanTest {

    private static final PlaybackPlan.Capabilities MP4 =
            PlaybackPlan.Capabilities.normalize(Set.of("mp4"), Set.of("h264", "hevc"), Set.of("aac"));

    /** A 1080p HEVC file of 1 GB lasting 1000 s: 8 Mbit/s on average. */
    private static MediaFile file() {
        MediaFile f = new MediaFile();
        f.setContainer("mp4");
        f.setVideoCodec("hevc");
        f.setAudioCodec("aac");
        f.setHeight(1080);
        f.setSize(1_000_000_000L);
        f.setDuration(1_000_000L);
        return f;
    }

    @Test
    void theOriginalWhenWithinTheLimits() {
        assertThat(DownloadService.plan(file(), MP4, new Transcoder.Limits(1080, 10_000_000L)).mode())
                .isEqualTo(PlaybackMode.DIRECT_PLAY);
        assertThat(DownloadService.plan(file(), MP4, Transcoder.Limits.NONE).mode()).isEqualTo(PlaybackMode.DIRECT_PLAY);
    }

    @Test
    void transcodedWhenTallerOrLargerThanAllowed() {
        PlaybackPlan tall = DownloadService.plan(file(), MP4, new Transcoder.Limits(720, null));
        assertThat(tall.mode()).isEqualTo(PlaybackMode.TRANSCODE);
        assertThat(tall.container()).isEqualTo("mp4");
        assertThat(tall.videoCodec()).isEqualTo("h264");
        assertThat(DownloadService.plan(file(), MP4, new Transcoder.Limits(null, 4_000_000L)).mode())
                .isEqualTo(PlaybackMode.TRANSCODE);
    }

    @Test
    void unknownSizesNeverForceATranscode() {
        MediaFile f = file();
        f.setHeight(null);
        f.setDuration(null);
        assertThat(DownloadService.plan(f, MP4, new Transcoder.Limits(480, 1_000_000L)).mode())
                .isEqualTo(PlaybackMode.DIRECT_PLAY);
    }

    @Test
    void preparedFilesKeepTheOriginalName() {
        assertThat(DownloadService.withExtension("Alien (1979).mkv", "mp4")).isEqualTo("Alien (1979).mp4");
        assertThat(DownloadService.withExtension("README", "webm")).isEqualTo("README.webm");
    }
}
