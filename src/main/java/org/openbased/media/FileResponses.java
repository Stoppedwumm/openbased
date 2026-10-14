package org.openbased.media;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.WebRequest;

/**
 * Serves a file for keeping on the client: {@code Content-Disposition: attachment}, a strong {@code ETag},
 * {@code Last-Modified}, and Range requests honoured only while {@code If-Range} still matches, so a resumed
 * download never joins parts of two versions.
 */
public final class FileResponses {

    private FileResponses() {
    }

    /** Returns {@code null} after a 304 Not Modified has been written. */
    public static ResponseEntity<Resource> attachment(Path path, String filename, String contentType,
            WebRequest request) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
        long size = attributes.size();
        // Second precision, because Last-Modified and If-Range dates have no more.
        long lastModified = attributes.lastModifiedTime().toMillis() / 1000 * 1000;
        String etag = etag(size, attributes.lastModifiedTime().toMillis());

        if (request.checkNotModified(etag, lastModified)) {
            // 304 Not Modified; the status and headers are already set.
            return null;
        }
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.parseMediaType(contentType))
                .cacheControl(CacheControl.noStore().cachePrivate())
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(filename))
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .eTag(etag)
                .lastModified(lastModified);
        String ifRange = request.getHeader(HttpHeaders.IF_RANGE);
        if (request.getHeader(HttpHeaders.RANGE) != null && ifRange != null
                && !ifRangeMatches(ifRange, etag, lastModified)) {
            // The client's partial copy is of another version: send the whole file. An InputStreamResource
            // keeps Spring from applying the Range header.
            return response.contentLength(size).body(new InputStreamResource(Files.newInputStream(path)));
        }
        return response.body(new FileSystemResource(path));
    }

    /**
     * {@code attachment} with an ASCII {@code filename} for old clients and the exact name in {@code filename*}
     * (RFC 6266). Spring's builder RFC 2047-encodes {@code filename}, which RFC 6266 does not allow.
     */
    static String contentDisposition(String filename) {
        StringBuilder ascii = new StringBuilder();
        StringBuilder encoded = new StringBuilder();
        filename.codePoints().forEach(c -> ascii.append(
                c >= 0x20 && c < 0x7f && c != '"' && c != '\\' ? (char) c : '_'));
        for (byte b : filename.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xff);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || "!#$&+-.^_`|~".indexOf(c) >= 0) {
                encoded.append(c);
            } else {
                encoded.append('%').append(String.format("%02X", (int) c));
            }
        }
        return "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded;
    }

    /** A strong validator from size and modification time, which change whenever the file is replaced. */
    static String etag(long size, long lastModifiedMillis) {
        return "\"" + Long.toHexString(size) + "-" + Long.toHexString(lastModifiedMillis) + "\"";
    }

    /** RFC 9110 13.1.5: an entity tag must match strongly; a date must equal Last-Modified exactly. */
    static boolean ifRangeMatches(String ifRange, String etag, long lastModifiedMillis) {
        String value = ifRange.trim();
        if (value.startsWith("\"") || value.startsWith("W/")) {
            return value.equals(etag);
        }
        try {
            return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
                    == lastModifiedMillis;
        } catch (DateTimeParseException e) {
            return false;
        }
    }
}
