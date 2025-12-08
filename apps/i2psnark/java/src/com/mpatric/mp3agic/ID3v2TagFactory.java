package com.mpatric.mp3agic;

public final class ID3v2TagFactory {
    private ID3v2TagFactory() {}

    /**
     * Creates an appropriate ID3v2 tag object based on the tag version in the byte data.
     *
     * @param bytes the byte array containing the ID3v2 tag data
     * @return an AbstractID3v2Tag implementation appropriate for the tag version
     * @throws NoSuchTagException if no valid ID3v2 tag is found in the data
     * @throws UnsupportedTagException if the tag version is not supported
     * @throws InvalidDataException if the tag data is invalid
     */
    public static AbstractID3v2Tag createTag(byte[] bytes)
            throws NoSuchTagException, UnsupportedTagException, InvalidDataException {
        sanityCheckTag(bytes);
        int majorVersion = bytes[AbstractID3v2Tag.MAJOR_VERSION_OFFSET];
        switch (majorVersion) {
            case 2:
                return createID3v22Tag(bytes);
            case 3:
                return new ID3v23Tag(bytes);
            case 4:
                return new ID3v24Tag(bytes);
        }
        throw new UnsupportedTagException("Tag version not supported");
    }

    private static AbstractID3v2Tag createID3v22Tag(byte[] bytes)
            throws NoSuchTagException, UnsupportedTagException, InvalidDataException {
        ID3v22Tag tag = new ID3v22Tag(bytes);
        if (tag.getFrameSets().isEmpty()) {
            tag = new ID3v22Tag(bytes, true);
        }
        return tag;
    }

    /**
     * Performs a sanity check on the ID3v2 tag data to ensure it's valid and supported.
     *
     * @param bytes the byte array containing the ID3v2 tag data
     * @throws NoSuchTagException if no valid ID3v2 tag identifier is found or the buffer is too short
     * @throws UnsupportedTagException if the tag version is not supported (only versions 2.2, 2.3, and 2.4 are supported)
     */
    public static void sanityCheckTag(byte[] bytes)
            throws NoSuchTagException, UnsupportedTagException {
        if (bytes.length < AbstractID3v2Tag.HEADER_LENGTH) {
            throw new NoSuchTagException("Buffer too short");
        }
        if (!AbstractID3v2Tag.TAG.equals(
                BufferTools.byteBufferToStringIgnoringEncodingIssues(
                        bytes, 0, AbstractID3v2Tag.TAG.length()))) {
            throw new NoSuchTagException();
        }
        int majorVersion = bytes[AbstractID3v2Tag.MAJOR_VERSION_OFFSET];
        if (majorVersion != 2 && majorVersion != 3 && majorVersion != 4) {
            int minorVersion = bytes[AbstractID3v2Tag.MINOR_VERSION_OFFSET];
            throw new UnsupportedTagException(
                    "Unsupported version 2." + majorVersion + "." + minorVersion);
        }
    }
}
