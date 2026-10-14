package org.openbased.media;

import java.nio.charset.StandardCharsets;
import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.openbased.security.AccessService;
import org.openbased.security.Scopes;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/media/{mediaId}/subtitles")
@Tag(name = "Media")
public class SubtitleController {

    private static final MediaType WEBVTT = new MediaType("text", "vtt", StandardCharsets.UTF_8);

    private final MediaLookup lookup;
    private final SubtitleService subtitles;
    private final AccessService access;

    public SubtitleController(MediaLookup lookup, SubtitleService subtitles, AccessService access) {
        this.lookup = lookup;
        this.subtitles = subtitles;
        this.access = access;
    }

    @GetMapping
    @Operation(summary = "List a file's subtitle tracks: embedded streams and .srt/.vtt files next to it")
    public List<SubtitleService.Track> list(@PathVariable String mediaId,
            @RequestParam(required = false) String fileId) {
        access.require(Scopes.MEDIA_READ);
        return subtitles.tracks(lookup.file(lookup.media(mediaId), fileId));
    }

    @GetMapping("/{trackId}")
    @Operation(summary = "Get a subtitle track as WebVTT; requires media.stream or media.download")
    public ResponseEntity<String> get(@PathVariable String mediaId, @PathVariable String trackId,
            @RequestParam(required = false) String fileId) {
        access.requireAny(Scopes.MEDIA_STREAM, Scopes.MEDIA_DOWNLOAD);
        MediaFile file = lookup.file(lookup.media(mediaId), fileId);
        return ResponseEntity.ok().contentType(WEBVTT).cacheControl(CacheControl.noCache().cachePrivate())
                .body(subtitles.webvtt(file, trackId));
    }
}
