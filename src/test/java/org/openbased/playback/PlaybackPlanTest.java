package org.openbased.playback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.openbased.common.ApiException;
import org.openbased.media.MediaFile;

class PlaybackPlanTest {

    private static MediaFile file(String container, String video, String audio) {
        MediaFile f = new MediaFile();
        f.setContainer(container);
        f.setVideoCodec(video);
        f.setAudioCodec(audio);
        return f;
    }

    private static PlaybackPlan.Capabilities caps(Set<String> c, Set<String> v, Set<String> a) {
        return PlaybackPlan.Capabilities.normalize(c, v, a);
    }

    @Test
    void directPlayWhenEverythingIsSupported() {
        PlaybackPlan plan = PlaybackPlan.decide(file("mp4", "h264", "aac"),
                caps(Set.of("mp4", "webm"), Set.of("h264", "vp9"), Set.of("aac", "opus")));
        assertThat(plan.mode()).isEqualTo(PlaybackMode.DIRECT_PLAY);
    }

    @Test
    void remuxWhenOnlyTheContainerIsUnsupported() {
        PlaybackPlan plan = PlaybackPlan.decide(file("mkv", "h264", "aac"),
                caps(Set.of("mp4", "webm"), Set.of("h264"), Set.of("aac")));
        assertThat(plan.mode()).isEqualTo(PlaybackMode.REMUX);
        assertThat(plan.container()).isEqualTo("mp4");
    }

    @Test
    void transcodeWhenCodecsAreUnsupported() {
        PlaybackPlan plan = PlaybackPlan.decide(file("mkv", "hevc", "dts"),
                caps(Set.of("mp4"), Set.of("h264"), Set.of("aac")));
        assertThat(plan.mode()).isEqualTo(PlaybackMode.TRANSCODE);
        assertThat(plan).extracting(PlaybackPlan::videoCodec, PlaybackPlan::audioCodec).containsExactly("h264", "aac");
    }

    @Test
    void transcodeToWebmForWebmOnlyClients() {
        PlaybackPlan plan = PlaybackPlan.decide(file("mkv", "hevc", "aac"),
                caps(Set.of("webm"), Set.of("vp9"), Set.of("opus")));
        assertThat(plan.mode()).isEqualTo(PlaybackMode.TRANSCODE);
        assertThat(plan.container()).isEqualTo("webm");
    }

    @Test
    void clientCodecAliasesAreNormalized() {
        PlaybackPlan plan = PlaybackPlan.decide(file("mp4", "hevc", "aac"),
                caps(Set.of("mp4"), Set.of("H265"), Set.of("mp4a")));
        assertThat(plan.mode()).isEqualTo(PlaybackMode.DIRECT_PLAY);
    }

    @Test
    void noCapabilitiesMeansDirectPlay() {
        assertThat(PlaybackPlan.decide(file("mkv", "hevc", "dts"), null).mode()).isEqualTo(PlaybackMode.DIRECT_PLAY);
    }

    @Test
    void unsatisfiableCapabilitiesAreRejected() {
        assertThatThrownBy(() -> PlaybackPlan.decide(file("mkv", "hevc", "dts"),
                caps(Set.of("ogg"), Set.of("theora"), Set.of("vorbis"))))
                .isInstanceOf(ApiException.class);
    }
}
