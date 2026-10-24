package com.mpatric.mp3agic;

/** An ID3v2.2 frame, with the shorter header. */
public class ID3v2ObseleteFrame extends ID3v2Frame {

    /** Size of the obselete frame header, in bytes. */
    private static final int HEADER_LENGTH = 6;
    /** Offset of the frame id in the header. */
    private static final int ID_OFFSET = 0;
    /** Length of the obselete three-character frame id. */
    private static final int ID_LENGTH = 3;
    /** Offset of the data length field in the header. */
    protected static final int DATA_LENGTH_OFFSET = 3;

    /**
     * Reads an obselete frame from a buffer.
     *
     * @param buffer the bytes to parse
     * @param offset where the frame header starts
     * @throws InvalidDataException if the frame header is corrupt
     */
    public ID3v2ObseleteFrame(byte[] buffer, int offset) throws InvalidDataException {
        super(buffer, offset);
    }

    /**
     * Creates an obselete frame with an id and data.
     *
     * @param id the three-character frame id
     * @param data the frame contents
     */
    public ID3v2ObseleteFrame(String id, byte[] data) {
        super(id, data);
    }

    @Override
    protected int unpackHeader(byte[] buffer, int offset) {
        id =
                BufferTools.byteBufferToStringIgnoringEncodingIssues(
                        buffer, offset + ID_OFFSET, ID_LENGTH);
        unpackDataLength(buffer, offset);
        return offset + HEADER_LENGTH;
    }

    @Override
    protected void unpackDataLength(byte[] buffer, int offset) {
        dataLength =
                BufferTools.unpackInteger(
                        (byte) 0,
                        buffer[offset + DATA_LENGTH_OFFSET],
                        buffer[offset + DATA_LENGTH_OFFSET + 1],
                        buffer[offset + DATA_LENGTH_OFFSET + 2]);
    }

    @Override
    public void packFrame(byte[] bytes, int offset) throws NotSupportedException {
        throw (new NotSupportedException("Packing Obselete frames is not supported"));
    }

    @Override
    public int getLength() {
        return dataLength + HEADER_LENGTH;
    }
}
