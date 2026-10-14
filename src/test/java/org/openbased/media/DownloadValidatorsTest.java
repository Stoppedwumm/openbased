package org.openbased.media;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DownloadValidatorsTest {

    private static final long MODIFIED = 1_760_000_000_000L;
    private static final String ETAG = FileResponses.etag(5000, MODIFIED);

    @Test
    void entityTagsMatchStrongly() {
        assertThat(FileResponses.ifRangeMatches(ETAG, ETAG, MODIFIED)).isTrue();
        assertThat(FileResponses.ifRangeMatches("W/" + ETAG, ETAG, MODIFIED)).isFalse();
        assertThat(FileResponses.ifRangeMatches(FileResponses.etag(5001, MODIFIED), ETAG, MODIFIED)).isFalse();
    }

    @Test
    void datesMustEqualLastModified() {
        assertThat(FileResponses.ifRangeMatches("Thu, 09 Oct 2025 08:53:20 GMT", ETAG, MODIFIED)).isTrue();
        assertThat(FileResponses.ifRangeMatches("Thu, 09 Oct 2025 08:53:21 GMT", ETAG, MODIFIED)).isFalse();
        assertThat(FileResponses.ifRangeMatches("yesterday", ETAG, MODIFIED)).isFalse();
    }

    @Test
    void contentDispositionHasAsciiFallbackAndExactName() {
        assertThat(FileResponses.contentDisposition("Heat (1995).mkv"))
                .isEqualTo("attachment; filename=\"Heat (1995).mkv\"; filename*=UTF-8''Heat%20%281995%29.mkv");
        assertThat(FileResponses.contentDisposition("Amélie \"2001\".mkv"))
                .isEqualTo("attachment; filename=\"Am_lie _2001_.mkv\"; filename*=UTF-8''Am%C3%A9lie%20%222001%22.mkv");
    }
}
