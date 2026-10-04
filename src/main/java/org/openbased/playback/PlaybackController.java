package org.openbased.playback;

import java.nio.file.Path;
import java.util.Set;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.openbased.common.ApiException;
import org.openbased.media.MediaFile;
import org.openbased.media.MediaItem;
import org.openbased.media.MediaLookup;
import org.openbased.security.AccessService;
import org.openbased.security.Scopes;
import org.openbased.user.User;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/playback")
@Tag(name = "Playback")
public class PlaybackController {

    private final PlaybackSessions sessions;
    private final Transcoder transcoder;
    private final MediaLookup lookup;
    private final AccessService access;

    public PlaybackController(PlaybackSessions sessions, Transcoder transcoder, MediaLookup lookup,
            AccessService access) {
        this.sessions = sessions;
        this.transcoder = transcoder;
        this.lookup = lookup;
        this.access = access;
    }

    public record Device(String name, String platform) {
    }

    public record CapabilitiesRequest(Set<String> containers, Set<String> videoCodecs, Set<String> audioCodecs) {
    }

    public record CreateSessionRequest(@NotBlank String mediaId, String fileId, Device device,
            CapabilitiesRequest capabilities) {
    }

    public record SessionResponse(String sessionId, String mediaId, String fileId, PlaybackMode mode,
            String container, String streamUrl) {
        static SessionResponse of(PlaybackSession s) {
            return new SessionResponse(s.id(), s.mediaId(), s.fileId(), s.plan().mode(), s.plan().container(),
                    "/api/v1/playback/" + s.id() + "/stream");
        }
    }

    @PostMapping("/sessions")
    @Operation(summary = "Create a playback session; the server picks direct play, remux or transcode")
    public ResponseEntity<SessionResponse> create(@Valid @RequestBody CreateSessionRequest request) {
        access.require(Scopes.MEDIA_STREAM);
        User user = access.user();
        MediaItem item = lookup.media(request.mediaId());
        MediaFile file = lookup.file(item, request.fileId());
        CapabilitiesRequest c = request.capabilities();
        PlaybackPlan plan = PlaybackPlan.decide(file, c == null ? null
                : PlaybackPlan.Capabilities.normalize(c.containers(), c.videoCodecs(), c.audioCodecs()));
        Device device = request.device();
        PlaybackSession session = sessions.start(user.getId(), item.getId(), file.getId(), plan,
                device == null ? null : device.name(), device == null ? null : device.platform());
        return ResponseEntity.status(HttpStatus.CREATED).body(SessionResponse.of(session));
    }

    @GetMapping("/{sessionId}")
    @Operation(summary = "Get a playback session")
    public SessionResponse get(@PathVariable String sessionId) {
        access.require(Scopes.MEDIA_STREAM);
        return SessionResponse.of(session(sessionId));
    }

    @GetMapping("/{sessionId}/stream")
    @Operation(summary = "Stream a playback session. Direct play supports Range; remux/transcode accept ?start=seconds")
    public ResponseEntity<Resource> stream(@PathVariable String sessionId,
            @RequestParam(defaultValue = "0") @PositiveOrZero double start) {
        access.require(Scopes.MEDIA_STREAM);
        PlaybackSession session = session(sessionId);
        // Re-check access on every request: library membership may have changed since the session started.
        MediaFile file = lookup.file(lookup.media(session.mediaId()), session.fileId());
        session.touch();
        if (session.plan().mode() == PlaybackMode.DIRECT_PLAY) {
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(MediaLookup.contentType(file.getContainer())))
                    .cacheControl(CacheControl.noStore())
                    .body(new FileSystemResource(Path.of(file.getPath())));
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(transcoder.contentType(session.plan(), file)))
                .cacheControl(CacheControl.noStore())
                .header("X-Playback-Mode", session.plan().mode().name())
                .body(new InputStreamResource(transcoder.start(session, file, start)));
    }

    @DeleteMapping("/{sessionId}")
    @Operation(summary = "Terminate a playback session")
    public ResponseEntity<Void> delete(@PathVariable String sessionId) {
        access.require(Scopes.MEDIA_STREAM);
        sessions.stop(session(sessionId));
        return ResponseEntity.noContent().build();
    }

    private PlaybackSession session(String sessionId) {
        return sessions.find(sessionId, access.user().getId()).orElseThrow(() -> ApiException.notFound(
                "PLAYBACK_SESSION_NOT_FOUND", "The playback session does not exist or has ended."));
    }
}
