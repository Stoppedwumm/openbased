package org.openbased.library;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.openbased.common.ApiException;
import org.openbased.common.Ids;
import org.openbased.job.Job;
import org.openbased.job.JobService;
import org.openbased.media.MediaItemRepository;
import org.openbased.media.MediaService;
import org.openbased.scan.LibraryScanner;
import org.openbased.security.AccessService;
import org.openbased.security.Scopes;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/libraries")
@Tag(name = "Libraries")
public class LibraryController {

    private final LibraryRepository libraries;
    private final MediaItemRepository items;
    private final MediaService mediaService;
    private final LibraryScanner scanner;
    private final JobService jobs;
    private final AccessService access;

    public LibraryController(LibraryRepository libraries, MediaItemRepository items, MediaService mediaService,
            LibraryScanner scanner, JobService jobs, AccessService access) {
        this.libraries = libraries;
        this.items = items;
        this.mediaService = mediaService;
        this.scanner = scanner;
        this.jobs = jobs;
        this.access = access;
    }

    public record LibraryAccess(boolean allUsers, Set<String> userIds) {
    }

    /** {@code paths} and {@code access} are only included for callers holding {@code library.write}. */
    public record LibraryResponse(String id, String name, LibraryType type, List<String> paths, LibraryAccess access,
            Instant createdAt, Instant lastScannedAt) {
    }

    public record LibraryList(List<LibraryResponse> items) {
    }

    public record CreateLibraryRequest(@NotBlank @Size(max = 200) String name, @NotNull LibraryType type,
            @NotEmpty List<@NotBlank String> paths, LibraryAccess access) {
    }

    public record UpdateLibraryRequest(@Size(min = 1, max = 200) String name, List<@NotBlank String> paths,
            LibraryAccess access) {
    }

    @GetMapping
    @Operation(summary = "List libraries visible to the current user")
    public LibraryList list() {
        access.require(Scopes.LIBRARY_READ);
        boolean admin = access.has(Scopes.LIBRARY_WRITE);
        Set<String> visible = access.accessibleLibraryIds();
        return new LibraryList(libraries.findAll().stream()
                .filter(l -> visible.contains(l.getId()))
                .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                .map(l -> toResponse(l, admin)).toList());
    }

    @GetMapping("/{libraryId}")
    @Operation(summary = "Get a library")
    public LibraryResponse get(@PathVariable String libraryId) {
        access.require(Scopes.LIBRARY_READ);
        return toResponse(access.library(libraryId), access.has(Scopes.LIBRARY_WRITE));
    }

    @PostMapping
    @Operation(summary = "Create a library")
    public ResponseEntity<LibraryResponse> create(@Valid @RequestBody CreateLibraryRequest request) {
        access.require(Scopes.LIBRARY_WRITE);
        Library library = new Library();
        library.setId(Ids.prefixed("lib"));
        library.setName(request.name().trim());
        library.setType(request.type());
        library.setPaths(validatePaths(request.paths()));
        applyAccess(library, request.access());
        library.setCreatedAt(Instant.now());
        libraries.save(library);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(library, true));
    }

    @PatchMapping("/{libraryId}")
    @Operation(summary = "Update a library's name, paths or access")
    public LibraryResponse update(@PathVariable String libraryId, @Valid @RequestBody UpdateLibraryRequest request) {
        access.require(Scopes.LIBRARY_WRITE);
        Library library = access.library(libraryId);
        if (request.name() != null) {
            library.setName(request.name().trim());
        }
        if (request.paths() != null) {
            library.setPaths(validatePaths(request.paths()));
        }
        applyAccess(library, request.access());
        libraries.save(library);
        return toResponse(library, true);
    }

    @DeleteMapping("/{libraryId}")
    @Transactional
    @Operation(summary = "Delete a library. Media files stay on disk unless deleteFiles=true")
    public ResponseEntity<Void> delete(@PathVariable String libraryId,
            @RequestParam(defaultValue = "false") boolean deleteFiles) {
        access.require(Scopes.LIBRARY_WRITE);
        if (deleteFiles) {
            access.require(Scopes.MEDIA_DELETE);
        }
        Library library = access.library(libraryId);
        if (jobs.isActive(LibraryScanner.JOB_TYPE, libraryId)) {
            throw ApiException.conflict("SCAN_IN_PROGRESS", "Wait for the running scan to finish first.");
        }
        items.findByLibraryId(libraryId).forEach(item -> mediaService.remove(item, deleteFiles));
        libraries.delete(library);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{libraryId}/scan")
    @Operation(summary = "Start a library scan")
    public ResponseEntity<Map<String, Object>> scan(@PathVariable String libraryId) {
        access.require(Scopes.LIBRARY_WRITE);
        Library library = access.library(libraryId);
        if (jobs.isActive(LibraryScanner.JOB_TYPE, libraryId)) {
            throw ApiException.conflict("SCAN_IN_PROGRESS", "A scan of this library is already running.");
        }
        Job job = jobs.submit(LibraryScanner.JOB_TYPE, access.principal().userId(), library.getId(),
                handle -> scanner.scan(library.getId(), handle));
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("jobId", job.getId(), "status", job.getStatus().name()));
    }

    private static List<String> validatePaths(List<String> paths) {
        List<String> normalized = new ArrayList<>();
        for (String raw : paths) {
            Path path = Path.of(raw);
            if (!path.isAbsolute()) {
                throw ApiException.unprocessable("INVALID_LIBRARY_PATH", "Library paths must be absolute: " + raw);
            }
            FolderAccess.problem(path.normalize()).ifPresent(problem -> {
                throw ApiException.unprocessable("INVALID_LIBRARY_PATH", problem);
            });
            normalized.add(path.normalize().toString());
        }
        return normalized;
    }

    private static void applyAccess(Library library, LibraryAccess requested) {
        if (requested == null) {
            return;
        }
        library.setAllUsers(requested.allUsers());
        library.setMemberIds(new HashSet<>(requested.userIds() == null ? Set.of() : requested.userIds()));
    }

    private static LibraryResponse toResponse(Library l, boolean admin) {
        return new LibraryResponse(l.getId(), l.getName(), l.getType(),
                admin ? List.copyOf(l.getPaths()) : null,
                admin ? new LibraryAccess(l.isAllUsers(), Set.copyOf(l.getMemberIds())) : null,
                l.getCreatedAt(), l.getLastScannedAt());
    }
}
