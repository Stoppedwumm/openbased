package org.openbased.metadata;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.openbased.config.OpenBasedProperties;
import org.openbased.plugin.api.MetadataProvider;

/** Built-in metadata provider for The Movie Database (movies and TV episodes). */
public class TmdbMetadataProvider implements MetadataProvider {

    private final OpenBasedProperties.Metadata.Tmdb config;
    private final ObjectMapper objectMapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    public TmdbMetadataProvider(OpenBasedProperties.Metadata.Tmdb config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
    }

    @Override
    public String id() {
        return "tmdb";
    }

    @Override
    public List<Result> search(String type, String query, Integer year) throws IOException, InterruptedException {
        boolean tv = "EPISODE".equals(type);
        StringBuilder path = new StringBuilder(tv ? "/search/tv" : "/search/movie")
                .append("?query=").append(encode(query));
        if (year != null) {
            path.append(tv ? "&first_air_date_year=" : "&year=").append(year);
        }
        JsonNode body = get(path.toString());
        List<Result> results = new ArrayList<>();
        for (JsonNode node : body.path("results")) {
            results.add(new Result(node.path("id").asText(),
                    text(node, tv ? "name" : "title"),
                    year(text(node, tv ? "first_air_date" : "release_date")),
                    image("w342", text(node, "poster_path"))));
        }
        return results;
    }

    @Override
    public Optional<Details> fetch(String type, String externalId) throws IOException, InterruptedException {
        if (!externalId.matches("\\d+")) {
            return Optional.empty();
        }
        boolean tv = "EPISODE".equals(type);
        JsonNode node = get((tv ? "/tv/" : "/movie/") + externalId);
        if (node == null || node.path("id").isMissingNode()) {
            return Optional.empty();
        }
        List<String> genres = new ArrayList<>();
        node.path("genres").forEach(g -> genres.add(g.path("name").asText()));
        Integer runtime = tv ? (node.path("episode_run_time").isArray() && !node.path("episode_run_time").isEmpty()
                ? node.path("episode_run_time").get(0).asInt() : null)
                : (node.hasNonNull("runtime") ? node.path("runtime").asInt() : null);
        return Optional.of(new Details(externalId,
                text(node, tv ? "name" : "title"),
                text(node, tv ? "original_name" : "original_title"),
                year(text(node, tv ? "first_air_date" : "release_date")),
                text(node, "overview"),
                genres,
                runtime,
                image("w780", text(node, "poster_path")),
                image("w1280", text(node, "backdrop_path")),
                Map.of()));
    }

    private JsonNode get(String pathAndQuery) throws IOException, InterruptedException {
        String separator = pathAndQuery.contains("?") ? "&" : "?";
        String url = config.getBaseUrl() + pathAndQuery + separator + "language=" + encode(config.getLanguage());
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json");
        String key = config.getApiKey();
        if (key.length() > 40) {
            request.header("Authorization", "Bearer " + key);
        } else {
            request.uri(URI.create(url + "&api_key=" + encode(key)));
        }
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            return null;
        }
        if (response.statusCode() / 100 != 2) {
            throw new IOException("TMDB returned HTTP " + response.statusCode());
        }
        return objectMapper.readTree(response.body());
    }

    private String image(String size, String path) {
        return path == null ? null : config.getImageBaseUrl() + "/" + size + path;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNull() || value.isMissingNode() || value.asText().isEmpty() ? null : value.asText();
    }

    private static Integer year(String date) {
        return date != null && date.length() >= 4 && date.substring(0, 4).matches("\\d{4}")
                ? Integer.valueOf(date.substring(0, 4)) : null;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
