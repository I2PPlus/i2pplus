package org.klomp.snark.web;

import com.mpatric.mp3agic.ID3v1;
import com.mpatric.mp3agic.ID3v2;
import com.mpatric.mp3agic.Mp3File;
import java.io.File;
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

    /**
     * Extracts artist and title metadata from an MP3 file's ID3 tags.
     *
     * @param f the MP3 file to read tags from
     * @return a string in the form {@code "Artist - Title"}, or null if no tags are found
     *         or an error occurs
     */
    public static String getTags(File f) {
        try {
            Mp3File mp3file = new Mp3File(f);
            String artist = null;
            String title = null;
            String album = null;
            String year = null;

            if (mp3file.hasId3v2Tag()) {
                ID3v2 id3v2Tag = mp3file.getId3v2Tag();
                artist = id3v2Tag.getArtist();
                album = id3v2Tag.getAlbum();
                year = id3v2Tag.getYear();
                title = id3v2Tag.getTitle();
            } else if (mp3file.hasId3v1Tag()) {
                ID3v1 id3v1Tag = mp3file.getId3v1Tag();
                artist = id3v1Tag.getArtist();
                album = id3v1Tag.getAlbum();
                year = id3v1Tag.getYear();
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
            return buf.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
