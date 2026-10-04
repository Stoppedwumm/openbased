package org.openbased.media;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.openbased.common.ApiException;
import org.openbased.security.AccessService;
import org.openbased.security.Scopes;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/artwork")
@Tag(name = "Artwork")
public class ArtworkController {

    private final ArtworkRepository artwork;
    private final MediaLookup lookup;
    private final AccessService access;

    public ArtworkController(ArtworkRepository artwork, MediaLookup lookup, AccessService access) {
        this.artwork = artwork;
        this.lookup = lookup;
        this.access = access;
    }

    /**
     * Returns an artwork image. Responses are cacheable for a day by the client, but marked private so
     * shared caches never serve them to callers that have not passed the access check.
     */
    @GetMapping("/{artworkId}")
    @Operation(summary = "Get an artwork image")
    public ResponseEntity<Resource> get(@PathVariable String artworkId) {
        access.require(Scopes.MEDIA_READ);
        Artwork art = artwork.findById(artworkId)
                .orElseThrow(() -> ApiException.notFound("ARTWORK_NOT_FOUND", "The requested artwork does not exist."));
        lookup.media(art.getMediaId());
        Path path = Path.of(art.getPath());
        if (!Files.isReadable(path)) {
            throw ApiException.notFound("ARTWORK_NOT_FOUND", "The requested artwork does not exist.");
        }
        return ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.parseMediaType(
                        art.getContentType() != null ? art.getContentType() : "image/jpeg"))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePrivate())
                .body(new FileSystemResource(path));
    }
}
