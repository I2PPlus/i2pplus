package org.klomp.snark;

import com.mpatric.mp3agic.ID3v1;
import com.mpatric.mp3agic.ID3v2;
import com.mpatric.mp3agic.Mp3File;

import net.i2p.data.DataHelper;


/**
 * Callback used to fetch data
 * @since 0.8.2
 */
public class Mp3Test
{
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("Usage: Mp3Test files...");
            System.exit(1);
        }
        for (int i = 0; i < args.length; i++) {
            long start = System.currentTimeMillis();
            Mp3File mp3file = new Mp3File(args[i]);

            if (mp3file.hasId3v2Tag()) {
              ID3v2 id3v2Tag = mp3file.getId3v2Tag();
              System.out.println("Track: " + id3v2Tag.getTrack());
              System.out.println("Artist: " + id3v2Tag.getArtist());
              System.out.println("Title: " + id3v2Tag.getTitle());
              System.out.println("Album: " + id3v2Tag.getAlbum());
              System.out.println("Year: " + id3v2Tag.getYear());
              System.out.println("Genre: " + id3v2Tag.getGenre() + " (" + id3v2Tag.getGenreDescription() + ")");
              System.out.println("Comment: " + id3v2Tag.getComment());
              System.out.println("Lyrics: " + id3v2Tag.getLyrics());
              System.out.println("Composer: " + id3v2Tag.getComposer());
              System.out.println("Publisher: " + id3v2Tag.getPublisher());
              System.out.println("Original artist: " + id3v2Tag.getOriginalArtist());
              System.out.println("Album artist: " + id3v2Tag.getAlbumArtist());
              System.out.println("Copyright: " + id3v2Tag.getCopyright());
              System.out.println("URL: " + id3v2Tag.getUrl());
              System.out.println("Encoder: " + id3v2Tag.getEncoder());
              byte[] albumImageData = id3v2Tag.getAlbumImage();
              if (albumImageData != null) {
                System.out.println("Have album image data, length: " + albumImageData.length + " bytes");
                System.out.println("Album image mime type: " + id3v2Tag.getAlbumImageMimeType());
              }
            } else if (mp3file.hasId3v1Tag()) {
              ID3v1 id3v1Tag = mp3file.getId3v1Tag();
              System.out.println("Track: " + id3v1Tag.getTrack());
              System.out.println("Artist: " + id3v1Tag.getArtist());
              System.out.println("Title: " + id3v1Tag.getTitle());
              System.out.println("Album: " + id3v1Tag.getAlbum());
              System.out.println("Year: " + id3v1Tag.getYear());
              System.out.println("Genre: " + id3v1Tag.getGenre() + " (" + id3v1Tag.getGenreDescription() + ")");
              System.out.println("Comment: " + id3v1Tag.getComment());
            }
            System.out.println("Tag extraction took: " +  DataHelper.formatDuration(System.currentTimeMillis() - start));
        }
    }
}