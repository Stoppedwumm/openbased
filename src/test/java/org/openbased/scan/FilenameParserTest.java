package org.openbased.scan;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.openbased.library.LibraryType;
import org.openbased.media.MediaType;

class FilenameParserTest {

    @Test
    void movieWithYearInParentheses() {
        FilenameParser.Parsed p = FilenameParser.parse(Path.of("/m/Interstellar (2014).mkv"), LibraryType.MOVIES);
        assertThat(p.type()).isEqualTo(MediaType.MOVIE);
        assertThat(p.title()).isEqualTo("Interstellar");
        assertThat(p.year()).isEqualTo(2014);
    }

    @Test
    void sceneStyleMovieName() {
        FilenameParser.Parsed p = FilenameParser.parse(Path.of("/m/Blade.Runner.2049.2017.1080p.BluRay.x264.mkv"),
                LibraryType.MOVIES);
        assertThat(p.title()).isEqualTo("Blade Runner 2049");
        assertThat(p.year()).isEqualTo(2017);
    }

    @Test
    void movieWithoutYear() {
        FilenameParser.Parsed p = FilenameParser.parse(Path.of("/m/Some_Home_Video.mp4"), LibraryType.MOVIES);
        assertThat(p.title()).isEqualTo("Some Home Video");
        assertThat(p.year()).isNull();
    }

    @Test
    void episode() {
        FilenameParser.Parsed p = FilenameParser.parse(Path.of("/tv/The.Expanse.S02E05.720p.mkv"), LibraryType.TV);
        assertThat(p.type()).isEqualTo(MediaType.EPISODE);
        assertThat(p.seriesTitle()).isEqualTo("The Expanse");
        assertThat(p.season()).isEqualTo(2);
        assertThat(p.episode()).isEqualTo(5);
        assertThat(p.title()).isEqualTo("The Expanse S02E05");
    }

    @Test
    void episodeNamedOnlyByNumberUsesFolders() {
        FilenameParser.Parsed p = FilenameParser.parse(Path.of("/tv/Dark (2017)/Season 1/S01E03.mkv"), LibraryType.TV);
        assertThat(p.seriesTitle()).isEqualTo("Dark");
        assertThat(p.season()).isEqualTo(1);
        assertThat(p.episode()).isEqualTo(3);
    }

    @Test
    void musicTrack() {
        FilenameParser.Parsed p = FilenameParser.parse(Path.of("/music/01 - Intro.flac"), LibraryType.MUSIC);
        assertThat(p.type()).isEqualTo(MediaType.TRACK);
        assertThat(p.title()).isEqualTo("01 - Intro");
    }
}
