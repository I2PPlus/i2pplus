package com.mpatric.mp3agic;

/** An ID3v2.3 tag. */
public class ID3v23Tag extends AbstractID3v2Tag {

    /** Version of the tag format this class reads and writes. */
    public static final String VERSION = "3.0";

    /** Starts an empty tag. */
    public ID3v23Tag() {
        super();
        version = VERSION;
    }

    /**
     * Reads a tag from the bytes of a file's header.
     *
     * @param buffer the header bytes
     * @throws InvalidDataException if the tag data is corrupt
     * @throws NoSuchTagException if the header is not an ID3v2 tag
     * @throws UnsupportedTagException if the tag is not version 2.3
     */
    public ID3v23Tag(byte[] buffer)
            throws NoSuchTagException, UnsupportedTagException, InvalidDataException {
        super(buffer);
    }

    @Override
    protected void unpackFlags(byte[] buffer) {
        unsynchronisation = BufferTools.checkBit(buffer[FLAGS_OFFSET], UNSYNCHRONISATION_BIT);
        extendedHeader = BufferTools.checkBit(buffer[FLAGS_OFFSET], EXTENDED_HEADER_BIT);
        experimental = BufferTools.checkBit(buffer[FLAGS_OFFSET], EXPERIMENTAL_BIT);
    }

    @Override
    protected void packFlags(byte[] bytes, int offset) {
        bytes[offset + FLAGS_OFFSET] =
                BufferTools.setBit(
                        bytes[offset + FLAGS_OFFSET], UNSYNCHRONISATION_BIT, unsynchronisation);
        bytes[offset + FLAGS_OFFSET] =
                BufferTools.setBit(
                        bytes[offset + FLAGS_OFFSET], EXTENDED_HEADER_BIT, extendedHeader);
        bytes[offset + FLAGS_OFFSET] =
                BufferTools.setBit(bytes[offset + FLAGS_OFFSET], EXPERIMENTAL_BIT, experimental);
    }
}
