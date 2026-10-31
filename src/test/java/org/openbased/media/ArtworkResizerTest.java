package org.openbased.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openbased.config.OpenBasedProperties;

class ArtworkResizerTest {

    @TempDir
    Path tmp;

    @Test
    void widthsAreRoundedUpToFixedOnes() {
        assertThat(ArtworkResizer.snap(1)).hasValue(92);
        assertThat(ArtworkResizer.snap(300)).hasValue(342);
        assertThat(ArtworkResizer.snap(342)).hasValue(342);
        assertThat(ArtworkResizer.snap(1280)).hasValue(1280);
        assertThat(ArtworkResizer.snap(1281)).isEmpty();
    }

    @Test
    void pruningKeepsOnlyCurrentVersionsOfExistingArtwork() throws Exception {
        Path source = Files.write(tmp.resolve("poster.jpg"), new byte[10]);
        Artwork art = new Artwork();
        art.setId("art_1");
        art.setPath(source.toString());
        ArtworkRepository repository = mock(ArtworkRepository.class);
        when(repository.findById(anyString())).thenReturn(Optional.empty());
        when(repository.findById("art_1")).thenReturn(Optional.of(art));
        OpenBasedProperties properties = new OpenBasedProperties();
        properties.setDataDir(tmp.resolve("data"));
        Path cache = Files.createDirectories(tmp.resolve("data").resolve("artwork-cache"));
        String version = Long.toHexString(10) + "-" + Long.toHexString(Files.getLastModifiedTime(source).toMillis());
        Path current = Files.write(cache.resolve("art_1-342-" + version + ".jpg"), new byte[1]);
        Path old = Files.write(cache.resolve("art_1-342-a-1.jpg"), new byte[1]);
        Path removed = Files.write(cache.resolve("art_2-92-a-1.jpg"), new byte[1]);
        Path writing = Files.write(cache.resolve("art_1-123.tmp"), new byte[1]);

        new ArtworkResizer(properties, repository).prune();

        assertThat(current).exists();
        assertThat(writing).exists();
        assertThat(old).doesNotExist();
        assertThat(removed).doesNotExist();
    }
}
