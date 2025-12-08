package com.mpatric.mp3agic;

import java.util.ArrayList;
import java.util.Map;

public interface ID3v2 extends ID3v1 {

    /**
     * Get the padding flag.
     *
     * @return true if padding is enabled
     */
    boolean getPadding();

    /**
     * Set the padding flag.
     *
     * @param padding true to enable padding
     */
    void setPadding(boolean padding);

    /**
     * Check if the tag has a footer.
     *
     * @return true if footer is present
     */
    boolean hasFooter();

    /**
     * Set the footer flag.
     *
     * @param footer true to include footer
     */
    void setFooter(boolean footer);

    /**
     * Check if unsynchronization is enabled.
     *
     * @return true if unsynchronization is enabled
     */
    boolean hasUnsynchronisation();

    /**
     * Set the unsynchronization flag.
     *
     * @param unsynchronisation true to enable unsynchronization
     */
    void setUnsynchronisation(boolean unsynchronisation);

    /**
     * Get the beats per minute.
     *
     * @return the BPM value, or -1 if not set
     */
    int getBPM();

    /**
     * Set the beats per minute.
     *
     * @param bpm the BPM value
     */
    void setBPM(int bpm);

    /**
     * Get the grouping information.
     *
     * @return the grouping, or null if not set
     */
    String getGrouping();

    /**
     * Set the grouping information.
     *
     * @param grouping the grouping to set
     */
    void setGrouping(String grouping);

    /**
     * Get the musical key.
     *
     * @return the musical key, or null if not set
     */
    String getKey();

    /**
     * Set the musical key.
     *
     * @param key the musical key to set
     */
    void setKey(String key);

    /**
     * Get the date.
     *
     * @return the date, or null if not set
     */
    String getDate();

    /**
     * Set the date.
     *
     * @param date the date to set
     */
    void setDate(String date);

    /**
     * Get the composer.
     *
     * @return the composer, or null if not set
     */
    String getComposer();

    /**
     * Set the composer.
     *
     * @param composer the composer to set
     */
    void setComposer(String composer);

    /**
     * Get the publisher.
     *
     * @return the publisher, or null if not set
     */
    String getPublisher();

    /**
     * Set the publisher.
     *
     * @param publisher the publisher to set
     */
    void setPublisher(String publisher);

    /**
     * Get the original artist.
     *
     * @return the original artist, or null if not set
     */
    String getOriginalArtist();

    /**
     * Set the original artist.
     *
     * @param originalArtist the original artist to set
     */
    void setOriginalArtist(String originalArtist);

    /**
     * Get the album artist.
     *
     * @return the album artist, or null if not set
     */
    String getAlbumArtist();

    /**
     * Set the album artist.
     *
     * @param albumArtist the album artist to set
     */
    void setAlbumArtist(String albumArtist);

    /**
     * Get the copyright information.
     *
     * @return the copyright, or null if not set
     */
    String getCopyright();

    /**
     * Set the copyright information.
     *
     * @param copyright the copyright to set
     */
    void setCopyright(String copyright);

    /**
     * Get the artist URL.
     *
     * @return the artist URL, or null if not set
     */
    String getArtistUrl();

    /**
     * Set the artist URL.
     *
     * @param url the artist URL to set
     */
    void setArtistUrl(String url);

    /**
     * Get the commercial URL.
     *
     * @return the commercial URL, or null if not set
     */
    String getCommercialUrl();

    /**
     * Set the commercial URL.
     *
     * @param url the commercial URL to set
     */
    void setCommercialUrl(String url);

    /**
     * Get the copyright URL.
     *
     * @return the copyright URL, or null if not set
     */
    String getCopyrightUrl();

    /**
     * Set the copyright URL.
     *
     * @param url the copyright URL to set
     */
    void setCopyrightUrl(String url);

    /**
     * Get the audio file URL.
     *
     * @return the audio file URL, or null if not set
     */
    String getAudiofileUrl();

    /**
     * Set the audio file URL.
     *
     * @param url the audio file URL to set
     */
    void setAudiofileUrl(String url);

    /**
     * Get the audio source URL.
     *
     * @return the audio source URL, or null if not set
     */
    String getAudioSourceUrl();

    /**
     * Set the audio source URL.
     *
     * @param url the audio source URL to set
     */
    void setAudioSourceUrl(String url);

    /**
     * Get the radio station URL.
     *
     * @return the radio station URL, or null if not set
     */
    String getRadiostationUrl();

    /**
     * Set the radio station URL.
     *
     * @param url the radio station URL to set
     */
    void setRadiostationUrl(String url);

    /**
     * Get the payment URL.
     *
     * @return the payment URL, or null if not set
     */
    String getPaymentUrl();

    /**
     * Set the payment URL.
     *
     * @param url the payment URL to set
     */
    void setPaymentUrl(String url);

    /**
     * Get the publisher URL.
     *
     * @return the publisher URL, or null if not set
     */
    String getPublisherUrl();

    /**
     * Set the publisher URL.
     *
     * @param url the publisher URL to set
     */
    void setPublisherUrl(String url);

    /**
     * Get the URL.
     *
     * @return the URL, or null if not set
     */
    String getUrl();

    /**
     * Set the URL.
     *
     * @param url the URL to set
     */
    void setUrl(String url);

    /**
     * Get the part of set information.
     *
     * @return the part of set, or null if not set
     */
    String getPartOfSet();

    /**
     * Set the part of set information.
     *
     * @param partOfSet the part of set to set
     */
    void setPartOfSet(String partOfSet);

    /**
     * Check if this is a compilation.
     *
     * @return true if this is a compilation
     */
    boolean isCompilation();

    /**
     * Set the compilation flag.
     *
     * @param compilation true if this is a compilation
     */
    void setCompilation(boolean compilation);

    /**
     * Get the chapters.
     *
     * @return the chapters, or null if not set
     */
    ArrayList<ID3v2ChapterFrameData> getChapters();

    /**
     * Set the chapters.
     *
     * @param chapters the chapters to set
     */
    void setChapters(ArrayList<ID3v2ChapterFrameData> chapters);

    /**
     * Get the chapter table of contents.
     *
     * @return the chapter TOC, or null if not set
     */
    ArrayList<ID3v2ChapterTOCFrameData> getChapterTOC();

    /**
     * Set the chapter table of contents.
     *
     * @param ctoc the chapter TOC to set
     */
    void setChapterTOC(ArrayList<ID3v2ChapterTOCFrameData> ctoc);

    /**
     * Get the encoder information.
     *
     * @return the encoder, or null if not set
     */
    String getEncoder();

    /**
     * Set the encoder information.
     *
     * @param encoder the encoder to set
     */
    void setEncoder(String encoder);

    /**
     * Get the album image data.
     *
     * @return the album image data, or null if not set
     */
    byte[] getAlbumImage();

    /**
     * Set the album image.
     *
     * @param albumImage the image data
     * @param mimeType the MIME type of the image
     */
    void setAlbumImage(byte[] albumImage, String mimeType);

    /**
     * Set the album image with full details.
     *
     * @param albumImage the image data
     * @param mimeType the MIME type of the image
     * @param imageType the image type
     * @param imageDescription the image description
     */
    void setAlbumImage(byte[] albumImage, String mimeType, byte imageType, String imageDescription);

    /** Clear album image. */
    void clearAlbumImage();

    /**
     * Get the album image MIME type.
     *
     * @return the MIME type, or null if not set
     */
    String getAlbumImageMimeType();

    /**
     * Get the Windows Media Player rating.
     *
     * @return the rating, or -1 if not set
     */
    int getWmpRating();

    /**
     * Set the Windows Media Player rating.
     *
     * @param rating the rating to set (0-5)
     */
    void setWmpRating(int rating);

    /**
     * Get the iTunes comment.
     *
     * @return the iTunes comment, or null if not set
     */
    String getItunesComment();

    /**
     * Set the iTunes comment.
     *
     * @param itunesComment the iTunes comment to set
     */
    void setItunesComment(String itunesComment);

    /**
     * Get the lyrics.
     *
     * @return the lyrics, or null if not set
     */
    String getLyrics();

    /**
     * Set the lyrics.
     *
     * @param lyrics the lyrics to set
     */
    void setLyrics(String lyrics);

    /**
     * Set genre from text. This method behaves different depending on the ID3 version. Prior to
     * ID3v2.4, the provided text must match a id3v1 genre description. With ID3v2.4, the genre is
     * written as free text.
     *
     * @param text genre string
     */
    void setGenreDescription(String text);

    /**
     * Get the data length.
     *
     * @return the data length in bytes
     */
    int getDataLength();

    /**
     * Get the total length.
     *
     * @return the total length in bytes
     */
    int getLength();

    /**
     * Check if obsolete format is used.
     *
     * @return true if obsolete format is used
     */
    boolean getObseleteFormat();

    /**
     * Get the frame sets.
     *
     * @return the frame sets map
     */
    Map<String, ID3v2FrameSet> getFrameSets();

    /**
     * Clear frame set by ID.
     *
     * @param id the frame set ID to clear
     */
    void clearFrameSet(String id);
}
