package org.openbased.scan;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.openbased.config.OpenBasedProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Reads technical stream information using ffprobe, when it is installed. */
@Component
public class MediaProbe {

    private static final Logger log = LoggerFactory.getLogger(MediaProbe.class);

    private final OpenBasedProperties properties;
    private final ObjectMapper objectMapper;
    private volatile Boolean available;

    public MediaProbe(OpenBasedProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** @param duration milliseconds */
    public record Info(Long duration, String videoCodec, String audioCodec, Integer width, Integer height) {
        static final Info EMPTY = new Info(null, null, null, null, null);
    }

    public Info probe(Path file) {
        if (Boolean.FALSE.equals(available)) {
            return Info.EMPTY;
        }
        try {
            Process process = new ProcessBuilder(List.of(properties.getPlayback().getFfprobe(), "-v", "error",
                    "-print_format", "json", "-show_format", "-show_streams", file.toAbsolutePath().toString()))
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            available = true;
            byte[] output = process.getInputStream().readAllBytes();
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return Info.EMPTY;
            }
            if (process.exitValue() != 0) {
                return Info.EMPTY;
            }
            return parse(objectMapper.readTree(output));
        } catch (IOException e) {
            if (available == null) {
                log.info("ffprobe is not available ({}); media will be added without technical details", e.getMessage());
            }
            available = false;
            return Info.EMPTY;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Info.EMPTY;
        }
    }

    static Info parse(JsonNode root) {
        Long duration = null;
        JsonNode format = root.path("format");
        if (format.hasNonNull("duration")) {
            try {
                duration = Math.round(Double.parseDouble(format.path("duration").asText()) * 1000);
            } catch (NumberFormatException ignored) {
                // Leave unknown.
            }
        }
        String video = null;
        String audio = null;
        Integer width = null;
        Integer height = null;
        for (JsonNode stream : root.path("streams")) {
            String kind = stream.path("codec_type").asText();
            boolean attachedPicture = stream.path("disposition").path("attached_pic").asInt() == 1;
            if ("video".equals(kind) && video == null && !attachedPicture) {
                video = stream.path("codec_name").asText(null);
                width = stream.hasNonNull("width") ? stream.path("width").asInt() : null;
                height = stream.hasNonNull("height") ? stream.path("height").asInt() : null;
            } else if ("audio".equals(kind) && audio == null) {
                audio = stream.path("codec_name").asText(null);
            }
        }
        return new Info(duration, video, audio, width, height);
    }
}
