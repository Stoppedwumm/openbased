package org.openbased.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openbased.common.ApiException;
import org.openbased.config.OpenBasedProperties;
import org.openbased.scan.MediaProbe;

class SubtitleServiceTest {

    private static final String SRT = """
            1
            00:00:01,000 --> 00:00:02,500
            Café au lait

            2
            00:00:03,000 --> 00:00:04,000 X1:0
            <i>Second</i>
            """;

    @TempDir
    Path tmp;

    @Test
    void srtBecomesWebVtt() {
        String vtt = SubtitleService.srtToVtt(SRT.replace("\n", "\r\n"));
        assertThat(vtt).startsWith("WEBVTT\n\n1\n00:00:01.000 --> 00:00:02.500\nCafé au lait\n")
                .contains("00:00:03.000 --> 00:00:04.000 X1:0\n<i>Second</i>").doesNotContain("\r");
    }

    @Test
    void textIsDecodedFromCommonEncodings() {
        assertThat(SubtitleService.decode("Café".getBytes(StandardCharsets.UTF_8))).isEqualTo("Café");
        assertThat(SubtitleService.decode(new byte[] { (byte) 0xef, (byte) 0xbb, (byte) 0xbf, 'h', 'i' }))
                .isEqualTo("hi");
        assertThat(SubtitleService.decode("Café".getBytes(Charset.forName("windows-1252")))).isEqualTo("Café");
        byte[] utf16 = "Café".getBytes(StandardCharsets.UTF_16LE);
        byte[] withBom = new byte[utf16.length + 2];
        withBom[0] = (byte) 0xff;
        withBom[1] = (byte) 0xfe;
        System.arraycopy(utf16, 0, withBom, 2, utf16.length);
        assertThat(SubtitleService.decode(withBom)).isEqualTo("Café");
    }

    @Test
    void sidecarsAreFoundAndTheirNamesRead() throws IOException {
        Path video = Files.write(tmp.resolve("Heat (1995).mkv"), new byte[1]);
        Files.writeString(tmp.resolve("Heat (1995).srt"), SRT);
        Files.writeString(tmp.resolve("Heat (1995).en.sdh.srt"), SRT);
        Files.writeString(tmp.resolve("Heat (1995).pt-br.forced.Commentary.vtt"), "WEBVTT\n");
        Files.writeString(tmp.resolve("Heat (1995).en.txt"), SRT);
        Files.writeString(tmp.resolve("Other.en.srt"), SRT);

        List<SubtitleService.Track> tracks = SubtitleService.sidecars(video).stream()
                .map(p -> SubtitleService.sidecar(video, p)).toList();
        assertThat(tracks).extracting(SubtitleService.Track::language).containsExactly("en", "pt-br", null);
        SubtitleService.Track sdh = tracks.get(0);
        assertThat(sdh.hearingImpaired()).isTrue();
        assertThat(sdh.format()).isEqualTo("srt");
        SubtitleService.Track forced = tracks.get(1);
        assertThat(forced.forced()).isTrue();
        assertThat(forced.title()).isEqualTo("Commentary");
        assertThat(forced.format()).isEqualTo("vtt");
        assertThat(tracks).extracting(SubtitleService.Track::id).allMatch(id -> id.matches("s[0-9a-f]{16}"))
                .doesNotHaveDuplicates();
    }

    @Test
    void embeddedTextTracksAreProbedAndExtracted() throws Exception {
        assumeTrue(available("ffmpeg") && available("ffprobe"), "ffmpeg is not installed");
        Path srt = Files.writeString(tmp.resolve("in.srt"), SRT);
        Path video = tmp.resolve("movie.mkv");
        Process ffmpeg = new ProcessBuilder("ffmpeg", "-v", "error", "-f", "lavfi", "-i", "testsrc=size=160x120:rate=5",
                "-i", srt.toString(), "-t", "5", "-map", "0:v", "-map", "1:s", "-c:v", "libx264", "-c:s", "srt",
                "-metadata:s:s:0", "language=eng", "-disposition:s:0", "forced", video.toString())
                .redirectErrorStream(true).start();
        assertThat(ffmpeg.waitFor()).isZero();
        OpenBasedProperties properties = new OpenBasedProperties();
        properties.setDataDir(tmp.resolve("data"));

        MediaProbe.Info info = new MediaProbe(properties, new ObjectMapper()).probe(video);
        assertThat(info.subtitles()).hasSize(1);
        EmbeddedSubtitle stream = info.subtitles().get(0);
        assertThat(stream.getCodec()).isEqualTo("subrip");
        assertThat(stream.getLanguage()).isEqualTo("eng");
        assertThat(stream.isForced()).isTrue();

        MediaFile file = new MediaFile();
        file.setId("file_1");
        file.setPath(video.toString());
        file.setSubtitles(info.subtitles());
        SubtitleService service = new SubtitleService(properties, null);
        SubtitleService.Track track = service.tracks(file).get(0);
        assertThat(track.id()).isEqualTo("e" + stream.getStreamIndex());
        assertThat(track.text()).isTrue();
        String vtt = service.webvtt(file, track.id());
        assertThat(vtt).startsWith("WEBVTT").contains("Café au lait").contains("00:01.000 --> 00:02.500");
        // Cached after the first extraction.
        assertThat(Files.list(tmp.resolve("data").resolve("subtitle-cache"))).hasSize(1);
        assertThat(service.webvtt(file, track.id())).isEqualTo(vtt);
    }

    @Test
    void imageTracksCannotBeServed() {
        MediaFile file = new MediaFile();
        file.setPath(tmp.resolve("movie.mkv").toString());
        file.setSubtitles(List.of(new EmbeddedSubtitle(3, "hdmv_pgs_subtitle", "eng", null, false, false, false)));
        SubtitleService service = new SubtitleService(new OpenBasedProperties(), null);
        assertThat(service.tracks(file).get(0).text()).isFalse();
        assertThatThrownBy(() -> service.webvtt(file, "e3")).isInstanceOf(ApiException.class)
                .hasMessageContaining("images");
        assertThatThrownBy(() -> service.webvtt(file, "e9")).isInstanceOf(ApiException.class);
    }

    private static boolean available(String tool) {
        try {
            return new ProcessBuilder(tool, "-version").redirectErrorStream(true).start().waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }
}
