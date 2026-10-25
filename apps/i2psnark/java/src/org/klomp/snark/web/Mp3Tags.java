package org.klomp.snark.web;

import com.mpatric.mp3agic.ID3v1;
import com.mpatric.mp3agic.ID3v2;
import com.mpatric.mp3agic.Mp3File;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import net.i2p.data.DataHelper;

/**
 * Utility class for extracting MP3 metadata tags for display in the I2PSnark web interface.
 *
 * <p>This class provides methods to read ID3v1 and ID3v2 tags from MP3 files, extracting
 * information such as artist name, track title, album name, and release year.
 *
 * <p>The extracted metadata is used to enhance the file browsing experience in I2PSnark by
 * displaying meaningful information about MP3 files instead of just filenames.
 *
 * <p>This class uses the mp3agic library for tag parsing and handles both ID3v1 and ID3v2 tag
 * formats, with preference for ID3v2 when available.
 *
 * @since 0.8.2
 */
public class Mp3Tags {

    /** Tag strings cached by path+mtime; the file list re-renders every refresh. */
    private static final int CACHE_MAX = 256;

    /** LRU cache of parsed tags, guarded by its own monitor. */
    private static final LinkedHashMap<String, String> _cache = new LinkedHashMap<String, String>(64, 0.75f, true) {
        /** Fixed id, the cache is never serialized. */
        private static final long serialVersionUID = 1L;

        /**
         * Evict the least-recently-used entry once the cache exceeds
         * {@link #CACHE_MAX}, so a library of MP3s cannot grow it without bound.
         *
         * @param eldest the least-recently-used entry
         * @return true to evict it
         */
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > CACHE_MAX;
        }
    };

    /** Utility class, not instantiable. */
    private Mp3Tags() {}

    /** Cache sentinel for "parsed, no tags" so tagless files are not re-parsed. */
    private static final String NO_TAGS = "";

    /**
     * Extracts artist and title metadata from an MP3 file's ID3 tags.
     * Results are cached by path and modification time, because the file
     * list re-reads every completed MP3 on every page refresh.
     *
     * @param f the MP3 file to read tags from
     * @return a string in the form {@code "Artist - Title"}, or null if no tags are found
     *         or an error occurs
     */
    public static String getTags(File f) {
        String key = f.getAbsolutePath() + ':' + f.lastModified();
        synchronized (_cache) {
            String cached = _cache.get(key);
            if (cached != null) {
                return NO_TAGS.equals(cached) ? null : cached;
            }
        }
        String rv = readTags(f);
        synchronized (_cache) {
            _cache.put(key, rv != null ? rv : NO_TAGS);
        }
        return rv;
    }

    /**
     * Parse the tags once; scanFile=false skips the full-frame scan, the
     * tags live in the header and footer.
     *
     * @param f the MP3 file to read tags from
     * @return the escaped tag string, or null if there are no tags
     */
    private static String readTags(File f) {
        try {
            Mp3File mp3file = new Mp3File(f, 32000, false);
            String artist = null;
            String title = null;
            String album = null;

            if (mp3file.hasId3v2Tag()) {
                ID3v2 id3v2Tag = mp3file.getId3v2Tag();
                artist = id3v2Tag.getArtist();
                album = id3v2Tag.getAlbum();
                title = id3v2Tag.getTitle();
            } else if (mp3file.hasId3v1Tag()) {
                ID3v1 id3v1Tag = mp3file.getId3v1Tag();
                artist = id3v1Tag.getArtist();
                album = id3v1Tag.getAlbum();
                title = id3v1Tag.getTitle();
            } else {
                return null;
            }
            if (artist == null && title == null && album == null) return null;
            StringBuilder buf = new StringBuilder(1024);
            if (artist != null) {
                buf.append(DataHelper.escapeHTML(artist));
            }
            if (title != null) {
                buf.append(" - ");
                buf.append(DataHelper.escapeHTML(title));
            }
            // album-only tags produce no display text; report "no tags"
            // rather than an empty string the caller would render as a
            // blank link instead of falling back to the filename
            return buf.length() > 0 ? buf.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
