package com.mpatric.mp3agic;

public interface ID3v1 {

    /**
     * Get ID3v1 version.
     *
     * @return version string
     */
    String getVersion();

    /**
     * Get track number.
     *
     * @return the track number as text, or null when the tag does not carry one
     */
    String getTrack();

    /**
     * Set track number.
     *
     * @param track the track number; only digits are written out, and a null
     *              value downgrades the tag to the ID3v1.0 layout
     */
    void setTrack(String track);

    /**
     * Get artist name.
     *
     * @return artist name
     */
    String getArtist();

    /**
     * Set artist name.
     *
     * @param artist artist name
     */
    void setArtist(String artist);

    /**
     * Get song title.
     *
     * @return the title text, trimmed of the padding an unset field carries
     */
    String getTitle();

    /**
     * Set song title.
     *
     * @param title the title text; anything past 30 bytes is discarded when the
     *              tag is written
     */
    void setTitle(String title);

    /**
     * Get album name.
     *
     * @return album name
     */
    String getAlbum();

    /**
     * Set album name.
     *
     * @param album album name
     */
    void setAlbum(String album);

    /**
     * Get year.
     *
     * @return the release year as text, or null when the tag does not carry one
     */
    String getYear();

    /**
     * Set year.
     *
     * @param year the release year stored in the 4-byte ID3v1 year field
     */
    void setYear(String year);

    /**
     * Get genre number.
     *
     * @return the numeric genre code, which indexes the ID3v1Genres table
     */
    int getGenre();

    /**
     * Set genre number.
     *
     * @param genre the numeric genre code; a code outside the ID3v1Genres table
     *              is written as-is and reads back as an unknown genre
     */
    void setGenre(int genre);

    /**
     * Get genre description.
     *
     * @return the name the genre code maps to, or "Unknown" if the code falls
     *         outside the ID3v1Genres table
     */
    String getGenreDescription();

    /**
     * Get comment.
     *
     * @return the free-text comment, or null when the tag does not carry one
     */
    String getComment();

    /**
     * Set comment.
     *
     * @param comment the free-text comment stored in the ID3v1 comment field
     */
    void setComment(String comment);

    /**
     * Convert ID3v1 tag to byte array.
     *
     * @return byte array representation of the ID3v1 tag
     * @throws NotSupportedException if the operation is not supported
     */
    byte[] toBytes() throws NotSupportedException;
}
