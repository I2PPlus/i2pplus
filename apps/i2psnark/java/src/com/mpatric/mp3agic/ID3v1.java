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
     * @return track number
     */
    String getTrack();

    /**
     * Set track number.
     *
     * @param track track number
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
     * @return song title
     */
    String getTitle();

    /**
     * Set song title.
     *
     * @param title song title
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
     * @return year
     */
    String getYear();

    /**
     * Set year.
     *
     * @param year year
     */
    void setYear(String year);

    /**
     * Get genre number.
     *
     * @return genre number
     */
    int getGenre();

    /**
     * Set genre number.
     *
     * @param genre genre number
     */
    void setGenre(int genre);

    /**
     * Get genre description.
     *
     * @return genre description
     */
    String getGenreDescription();

    /**
     * Get comment.
     *
     * @return comment
     */
    String getComment();

    /**
     * Set comment.
     *
     * @param comment comment
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
