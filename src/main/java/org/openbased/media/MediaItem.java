package org.openbased.media;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;

@Entity
@Table(name = "media_items", indexes = { @Index(columnList = "libraryId"), @Index(columnList = "sortTitle") })
public class MediaItem {

    @Id
    private String id;

    @Column(nullable = false)
    private String libraryId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MediaType type;

    @Column(nullable = false)
    private String title;

    private String sortTitle;
    private String originalTitle;

    @Column(name = "release_year")
    private Integer year;

    @Lob
    @Column(length = 100_000)
    private String overview;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "media_genres", joinColumns = @JoinColumn(name = "media_id"))
    @OrderColumn(name = "position")
    @Column(name = "genre")
    private List<String> genres = new ArrayList<>();

    /** Runtime in minutes, as reported by metadata providers. */
    private Integer runtime;

    /** Duration in milliseconds, as measured from the media file. */
    private Long duration;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "media_external_ids", joinColumns = @JoinColumn(name = "media_id"))
    @MapKeyColumn(name = "provider")
    @Column(name = "external_id")
    private Map<String, String> externalIds = new HashMap<>();

    private String posterId;
    private String backdropId;

    private String seriesTitle;
    private Integer seasonNumber;
    private Integer episodeNumber;

    private Instant addedAt;
    private Instant updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getLibraryId() { return libraryId; }
    public void setLibraryId(String libraryId) { this.libraryId = libraryId; }
    public MediaType getType() { return type; }
    public void setType(MediaType type) { this.type = type; }
    public String getTitle() { return title; }
    public void setTitle(String title) {
        this.title = title;
        this.sortTitle = sortKey(title);
    }
    public String getSortTitle() { return sortTitle; }
    public String getOriginalTitle() { return originalTitle; }
    public void setOriginalTitle(String originalTitle) { this.originalTitle = originalTitle; }
    public Integer getYear() { return year; }
    public void setYear(Integer year) { this.year = year; }
    public String getOverview() { return overview; }
    public void setOverview(String overview) { this.overview = overview; }
    public List<String> getGenres() { return genres; }
    public void setGenres(List<String> genres) { this.genres = genres; }
    public Integer getRuntime() { return runtime; }
    public void setRuntime(Integer runtime) { this.runtime = runtime; }
    public Long getDuration() { return duration; }
    public void setDuration(Long duration) { this.duration = duration; }
    public Map<String, String> getExternalIds() { return externalIds; }
    public void setExternalIds(Map<String, String> externalIds) { this.externalIds = externalIds; }
    public String getPosterId() { return posterId; }
    public void setPosterId(String posterId) { this.posterId = posterId; }
    public String getBackdropId() { return backdropId; }
    public void setBackdropId(String backdropId) { this.backdropId = backdropId; }
    public String getSeriesTitle() { return seriesTitle; }
    public void setSeriesTitle(String seriesTitle) { this.seriesTitle = seriesTitle; }
    public Integer getSeasonNumber() { return seasonNumber; }
    public void setSeasonNumber(Integer seasonNumber) { this.seasonNumber = seasonNumber; }
    public Integer getEpisodeNumber() { return episodeNumber; }
    public void setEpisodeNumber(Integer episodeNumber) { this.episodeNumber = episodeNumber; }
    public Instant getAddedAt() { return addedAt; }
    public void setAddedAt(Instant addedAt) { this.addedAt = addedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    static String sortKey(String title) {
        if (title == null) {
            return null;
        }
        String t = title.trim().toLowerCase(java.util.Locale.ROOT);
        for (String article : new String[] { "the ", "a ", "an " }) {
            if (t.startsWith(article) && t.length() > article.length()) {
                return t.substring(article.length());
            }
        }
        return t;
    }
}
