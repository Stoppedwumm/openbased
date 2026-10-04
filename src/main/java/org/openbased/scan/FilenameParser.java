package org.openbased.scan;

import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openbased.library.LibraryType;
import org.openbased.media.MediaType;

/** Derives titles, years and episode numbers from file and folder names. */
public final class FilenameParser {

    private static final Pattern EPISODE = Pattern.compile("(?i)^(.*?)[\\s._-]*s(\\d{1,2})[\\s._-]*e(\\d{1,3})");
    private static final Pattern EPISODE_X = Pattern.compile("(?i)^(.*?)[\\s._-]+(\\d{1,2})x(\\d{2,3})(?:\\D|$)");
    private static final Pattern YEAR = Pattern.compile("^(.*)[\\s._\\[(-]+((?:19|20)\\d{2})(?:[\\s._\\])-]|$)");
    private static final Pattern SEASON_DIR = Pattern.compile("(?i)^(season|series|staffel)[\\s._-]*\\d+$|^s\\d{1,2}$");

    private FilenameParser() {
    }

    public record Parsed(MediaType type, String title, Integer year, String seriesTitle, Integer season,
            Integer episode) {
    }

    public static Parsed parse(Path file, LibraryType libraryType) {
        String base = stripExtension(file.getFileName().toString());
        if (libraryType == LibraryType.MUSIC) {
            return new Parsed(MediaType.TRACK, clean(base), null, null, null, null);
        }
        Matcher episode = EPISODE.matcher(base);
        if (!episode.find()) {
            episode = EPISODE_X.matcher(base);
            if (!episode.find()) {
                episode = null;
            }
        }
        if (episode != null && libraryType != LibraryType.MOVIES) {
            String series = clean(stripYear(episode.group(1)));
            if (series.isEmpty()) {
                series = seriesFromFolders(file);
            }
            int season = Integer.parseInt(episode.group(2));
            int number = Integer.parseInt(episode.group(3));
            String title = String.format("%s S%02dE%02d", series, season, number);
            return new Parsed(MediaType.EPISODE, title, null, series, season, number);
        }
        Matcher year = YEAR.matcher(base);
        if (year.find() && !clean(year.group(1)).isEmpty()) {
            MediaType type = libraryType == LibraryType.OTHER ? MediaType.VIDEO : MediaType.MOVIE;
            return new Parsed(type, clean(year.group(1)), Integer.valueOf(year.group(2)), null, null, null);
        }
        MediaType type = libraryType == LibraryType.MOVIES ? MediaType.MOVIE : MediaType.VIDEO;
        return new Parsed(type, clean(base), null, null, null, null);
    }

    private static String seriesFromFolders(Path file) {
        Path parent = file.getParent();
        if (parent != null && parent.getFileName() != null && SEASON_DIR.matcher(parent.getFileName().toString()).matches()) {
            parent = parent.getParent();
        }
        return parent != null && parent.getFileName() != null ? clean(stripYear(parent.getFileName().toString())) : "Unknown";
    }

    private static String stripYear(String value) {
        return value.replaceAll("[\\s._-]*[(\\[]?(19|20)\\d{2}[)\\]]?$", "");
    }

    static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    static String clean(String value) {
        String s = value.replaceAll("[._]+", " ").replaceAll("\\s+", " ").trim();
        return s.replaceAll("[\\s-]+$", "").replaceAll("^[\\s-]+", "");
    }
}
