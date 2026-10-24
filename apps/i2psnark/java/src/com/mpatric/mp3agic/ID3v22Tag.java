package com.mpatric.mp3agic;

/** An ID3v2.2 tag. */
public class ID3v22Tag extends AbstractID3v2Tag {

    /** Version of the tag format this class reads and writes. */
    public static final String VERSION = "2.0";

    /** Starts an empty tag. */
    public ID3v22Tag() {
        super();
        version = VERSION;
    }

    /**
     * Reads a tag from the bytes of a file's header.
     *
     * @param buffer the header bytes
     * @throws InvalidDataException if the tag data is corrupt
     * @throws NoSuchTagException if the header is not an ID3v2 tag
     * @throws UnsupportedTagException if the tag is not version 2.2
     */
    public ID3v22Tag(byte[] buffer)
            throws NoSuchTagException, UnsupportedTagException, InvalidDataException {
        super(buffer);
    }

    /**
     * Reads a tag, choosing the obselete picture layout when asked.
     *
     * @param buffer the header bytes
     * @param obseleteFormat whether the picture frames use the older layout
     * @throws InvalidDataException if the tag data is corrupt
     * @throws NoSuchTagException if the header is not an ID3v2 tag
     * @throws UnsupportedTagException if the tag is not version 2.2
     */
    public ID3v22Tag(byte[] buffer, boolean obseleteFormat)
            throws NoSuchTagException, UnsupportedTagException, InvalidDataException {
        super(buffer, obseleteFormat);
    }

    @Override
    protected void unpackFlags(byte[] bytes) {
        unsynchronisation = BufferTools.checkBit(bytes[FLAGS_OFFSET], UNSYNCHRONISATION_BIT);
        compression = BufferTools.checkBit(bytes[FLAGS_OFFSET], COMPRESSION_BIT);
    }

    @Override
    protected void packFlags(byte[] bytes, int offset) {
        bytes[offset + FLAGS_OFFSET] =
                BufferTools.setBit(
                        bytes[offset + FLAGS_OFFSET], UNSYNCHRONISATION_BIT, unsynchronisation);
        bytes[offset + FLAGS_OFFSET] =
                BufferTools.setBit(bytes[offset + FLAGS_OFFSET], COMPRESSION_BIT, compression);
    }
}
