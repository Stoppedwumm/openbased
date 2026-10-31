package org.openbased.media;

import jakarta.persistence.Embeddable;

/** A subtitle stream inside a media file, as reported by ffprobe. */
@Embeddable
public class EmbeddedSubtitle {

    /** ffprobe's stream index, used with {@code -map 0:<index>}. */
    private int streamIndex;
    private String codec;
    /** ISO 639 code from the stream's language tag, when set. */
    private String language;
    private String title;
    private boolean forced;
    private boolean defaultTrack;
    private boolean hearingImpaired;

    public EmbeddedSubtitle() {
    }

    public EmbeddedSubtitle(int streamIndex, String codec, String language, String title, boolean forced,
            boolean defaultTrack, boolean hearingImpaired) {
        this.streamIndex = streamIndex;
        this.codec = codec;
        this.language = language;
        this.title = title;
        this.forced = forced;
        this.defaultTrack = defaultTrack;
        this.hearingImpaired = hearingImpaired;
    }

    public int getStreamIndex() { return streamIndex; }
    public String getCodec() { return codec; }
    public String getLanguage() { return language; }
    public String getTitle() { return title; }
    public boolean isForced() { return forced; }
    public boolean isDefaultTrack() { return defaultTrack; }
    public boolean isHearingImpaired() { return hearingImpaired; }
}
