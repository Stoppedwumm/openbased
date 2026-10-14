package org.openbased.media;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DownloadValidatorsTest {

    private static final long MODIFIED = 1_760_000_000_000L;
    private static final String ETAG = MediaController.etag(5000, MODIFIED);

    @Test
    void entityTagsMatchStrongly() {
        assertThat(MediaController.ifRangeMatches(ETAG, ETAG, MODIFIED)).isTrue();
        assertThat(MediaController.ifRangeMatches("W/" + ETAG, ETAG, MODIFIED)).isFalse();
        assertThat(MediaController.ifRangeMatches(MediaController.etag(5001, MODIFIED), ETAG, MODIFIED)).isFalse();
    }

    @Test
    void datesMustEqualLastModified() {
        assertThat(MediaController.ifRangeMatches("Thu, 09 Oct 2025 08:53:20 GMT", ETAG, MODIFIED)).isTrue();
        assertThat(MediaController.ifRangeMatches("Thu, 09 Oct 2025 08:53:21 GMT", ETAG, MODIFIED)).isFalse();
        assertThat(MediaController.ifRangeMatches("yesterday", ETAG, MODIFIED)).isFalse();
    }

    @Test
    void contentDispositionHasAsciiFallbackAndExactName() {
        assertThat(MediaController.contentDisposition("Heat (1995).mkv"))
                .isEqualTo("attachment; filename=\"Heat (1995).mkv\"; filename*=UTF-8''Heat%20%281995%29.mkv");
        assertThat(MediaController.contentDisposition("Amélie \"2001\".mkv"))
                .isEqualTo("attachment; filename=\"Am_lie _2001_.mkv\"; filename*=UTF-8''Am%C3%A9lie%20%222001%22.mkv");
    }
}
