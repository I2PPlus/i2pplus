package org.klomp.snark.web;

import java.io.File;

import com.mpatric.mp3agic.ID3v1;
import com.mpatric.mp3agic.ID3v2;
import com.mpatric.mp3agic.Mp3File;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;


/**
 * Callback used to fetch data
 * @since 0.8.2
 */
public class Mp3Tags
{
    public static String getTags(File f) {
        try {
            Mp3File mp3file = new Mp3File(f);
            String artist = null, track = null, title = null, album = null,
                   year = null, composer = null, genre = null, original = null;

            if (mp3file.hasId3v2Tag()) {
              ID3v2 id3v2Tag = mp3file.getId3v2Tag();
              artist = id3v2Tag.getArtist();
              album = id3v2Tag.getAlbum();
              year = id3v2Tag.getYear();
              title = id3v2Tag.getTitle();
/*              track = id3v2Tag.getTrack();
              composer = id3v2Tag.getComposer();
              genre = id3v2Tag.getGenreDescription();
              original = id3v2Tag.getOriginalArtist();
*/
/*
              Comment: " + id3v2Tag.getComment());
              Lyrics: " + id3v2Tag.getLyrics());
              Publisher: " + id3v2Tag.getPublisher());
              Album artist: " + id3v2Tag.getAlbumArtist());
              Copyright: " + id3v2Tag.getCopyright());
              URL: " + id3v2Tag.getUrl());
              Encoder: " + id3v2Tag.getEncoder());
              byte[] albumImageData = id3v2Tag.getAlbumImage();
              if (albumImageData != null) {
                Have album image data, length: " + albumImageData.length + " bytes");
                Album image mime type: " + id3v2Tag.getAlbumImageMimeType());
              }
*/
            } else if (mp3file.hasId3v1Tag()) {
              ID3v1 id3v1Tag = mp3file.getId3v1Tag();
              artist = id3v1Tag.getArtist();
              album = id3v1Tag.getAlbum();
              year = id3v1Tag.getYear();
              title = id3v1Tag.getTitle();
/*
              track = id3v1Tag.getTrack();
              Comment: " + id3v1Tag.getComment());
*/
            } else {
                return null;
            }
/*            if (artist == null && title == null && album == null &&
                composer == null && genre == null && original == null)
*/
            if (artist == null && title == null && album == null)
                return null;
            StringBuilder buf = new StringBuilder(1024);
/*
            buf.append("<span class=\"tags\"");
            if (album != null || year != null) {
                buf.append(" title=\"");
                if (album != null) {
                    buf.append("Album: ");
                    buf.append(DataHelper.escapeHTML(album));
                }
                if (year != null) {
                    buf.append(" (");
                    buf.append(DataHelper.escapeHTML(year));
                    buf.append(") ");
                }
                buf.append("\"");
            }
            buf.append(">");
*/
            if (artist != null) {
                buf.append(DataHelper.escapeHTML(artist));
            }

/*
            if (album != null) {
                buf.append(" - ");
                buf.append(DataHelper.escapeHTML(album));
            }

            if (album != null && year != null) {
                buf.append(" (");
                buf.append(DataHelper.escapeHTML(year));
                buf.append(") ");
            }
*/

            if (title != null) {
                buf.append(" - ");
                buf.append(DataHelper.escapeHTML(title));
            }

/*
            if (year != null) {
                buf.append(" (");
                buf.append(DataHelper.escapeHTML(year));
                buf.append(") ");
            }
*/

/*
                if (year != null)
                    buf.append(" (").append(DataHelper.escapeHTML(year)).append(')');
                if (track != null)
                    // ngettext
                    buf.append(' ').append(_t("track")).append(' ').append(DataHelper.escapeHTML(track));
            }

            if (composer != null) {
                if (br) buf.append(" - "); else br = true;
                buf.append(_t("Composer")).append(": ").append(DataHelper.escapeHTML(composer));
            }
            if (original != null) {
                if (br) buf.append(" - "); else br = true;
                buf.append(_t("Original Artist")).append(": ").append(DataHelper.escapeHTML(original));
            }
            if (genre != null) {
                if (br) buf.append(" - "); else br = true;
                buf.append(DataHelper.escapeHTML(genre));
            }
*/
//            buf.append("</span>");
            return buf.toString();
        } catch (Exception e) {
            return null;
        }
    }

    // temp
    private static String _t(String s) { return s; }

}