package com.mpatric.mp3agic;

import java.io.UnsupportedEncodingException;
import java.util.Arrays;
import java.util.Objects;

/** One frame of an ID3v2 tag. */
public class ID3v2Frame {

    /** Length of a frame header, in bytes. */
    private static final int HEADER_LENGTH = 10;
    /** Offset of the frame id in the header. */
    private static final int ID_OFFSET = 0;
    /** Length of the frame id, in bytes. */
    private static final int ID_LENGTH = 4;
    /** Offset of the data length in the header. */
    protected static final int DATA_LENGTH_OFFSET = 4;
    /** Offset of the first flags byte. */
    private static final int FLAGS1_OFFSET = 8;
    /** Offset of the second flags byte. */
    private static final int FLAGS2_OFFSET = 9;
    /** Bit position of the preserve-tag flag. */
    private static final int PRESERVE_TAG_BIT = 6;
    /** Bit position of the preserve-file flag. */
    private static final int PRESERVE_FILE_BIT = 5;
    /** Bit position of the read-only flag. */
    private static final int READ_ONLY_BIT = 4;
    /** Bit position of the grouping-identity flag. */
    private static final int GROUP_BIT = 6;
    /** Bit position of the compression flag. */
    private static final int COMPRESSION_BIT = 3;
    /** Bit position of the encryption flag. */
    private static final int ENCRYPTION_BIT = 2;
    /** Bit position of the unsynchronisation flag. */
    private static final int UNSYNCHRONISATION_BIT = 1;
    /** Bit position of the data-length indicator flag. */
    private static final int DATA_LENGTH_INDICATOR_BIT = 0;

    /** The four character frame id. */
    protected String id;
    /** Length of the frame data, in bytes. */
    protected int dataLength = 0;
    /** The frame data bytes. */
    protected byte[] data = null;
    /** Whether the tag must survive editing. */
    private boolean preserveTag = false;
    /** Whether the file must survive editing. */
    private boolean preserveFile = false;
    /** Whether the frame is read-only. */
    private boolean readOnly = false;
    /** Whether the frame belongs to a group. */
    private boolean group = false;
    /** Whether the frame data is compressed. */
    private boolean compression = false;
    /** Whether the frame data is encrypted. */
    private boolean encryption = false;
    /** Whether the frame data is unsynchronised. */
    private boolean unsynchronisation = false;
    /** Whether the data length indicator is present. */
    private boolean dataLengthIndicator = false;

    /**
     * Constructs an ID3v2Frame from the specified byte buffer at the given offset.
     *
     * @param buffer the byte buffer containing the frame data
     * @param offset the offset in the buffer where the frame starts
     * @throws InvalidDataException if the frame data is invalid
     */
    public ID3v2Frame(byte[] buffer, int offset) throws InvalidDataException {
        unpackFrame(buffer, offset);
    }

    /**
     * Constructs an ID3v2Frame with the specified ID and data.
     *
     * @param id the 4-character frame identifier
     * @param data the frame data
     */
    public ID3v2Frame(String id, byte[] data) {
        this.id = id;
        this.data = data;
        dataLength = data.length;
    }

    /**
     * Reads one frame from the buffer.
     *
     * @param buffer the tag bytes
     * @param offset the frame's offset in the buffer
     * @throws InvalidDataException if the frame is invalid
     */
    protected final void unpackFrame(byte[] buffer, int offset) throws InvalidDataException {
        int dataOffset = unpackHeader(buffer, offset);
        sanityCheckUnpackedHeader();
        data = BufferTools.copyBuffer(buffer, dataOffset, dataLength);
    }

    /**
     * Reads the frame header from the buffer.
     *
     * @param buffer the tag bytes
     * @param offset the header's offset in the buffer
     * @return the offset of the frame data
     */
    protected int unpackHeader(byte[] buffer, int offset) {
        id =
                BufferTools.byteBufferToStringIgnoringEncodingIssues(
                        buffer, offset + ID_OFFSET, ID_LENGTH);
        unpackDataLength(buffer, offset);
        unpackFlags(buffer, offset);
        return offset + HEADER_LENGTH;
    }

    /**
     * Reads the frame data length from the header.
     *
     * @param buffer the tag bytes
     * @param offset the length field's offset in the buffer
     */
    protected void unpackDataLength(byte[] buffer, int offset) {
        dataLength =
                BufferTools.unpackInteger(
                        buffer[offset + DATA_LENGTH_OFFSET],
                        buffer[offset + DATA_LENGTH_OFFSET + 1],
                        buffer[offset + DATA_LENGTH_OFFSET + 2],
                        buffer[offset + DATA_LENGTH_OFFSET + 3]);
    }

    /**
     * Reads the frame flags from the header.
     *
     * @param buffer the tag bytes
     * @param offset the flags' offset in the buffer
     */
    private void unpackFlags(byte[] buffer, int offset) {
        preserveTag = BufferTools.checkBit(buffer[offset + FLAGS1_OFFSET], PRESERVE_TAG_BIT);
        preserveFile = BufferTools.checkBit(buffer[offset + FLAGS1_OFFSET], PRESERVE_FILE_BIT);
        readOnly = BufferTools.checkBit(buffer[offset + FLAGS1_OFFSET], READ_ONLY_BIT);
        group = BufferTools.checkBit(buffer[offset + FLAGS2_OFFSET], GROUP_BIT);
        compression = BufferTools.checkBit(buffer[offset + FLAGS2_OFFSET], COMPRESSION_BIT);
        encryption = BufferTools.checkBit(buffer[offset + FLAGS2_OFFSET], ENCRYPTION_BIT);
        unsynchronisation =
                BufferTools.checkBit(buffer[offset + FLAGS2_OFFSET], UNSYNCHRONISATION_BIT);
        dataLengthIndicator =
                BufferTools.checkBit(buffer[offset + FLAGS2_OFFSET], DATA_LENGTH_INDICATOR_BIT);
    }

    /**
     * Rejects frames with unsupported features.
     *
     * @throws InvalidDataException if the frame is invalid
     */
    protected void sanityCheckUnpackedHeader() throws InvalidDataException {
        for (int i = 0; i < id.length(); i++) {
            if (!((id.charAt(i) >= 'A' && id.charAt(i) <= 'Z')
                    || (id.charAt(i) >= '0' && id.charAt(i) <= '9'))) {
                throw new InvalidDataException("Not a valid frame - invalid tag " + id);
            }
        }
    }

    /**
     * Converts the frame to a byte array.
     *
     * @return the frame as a byte array
     * @throws NotSupportedException if the operation is not supported
     */
    public byte[] toBytes() throws NotSupportedException {
        byte[] bytes = new byte[getLength()];
        packFrame(bytes, 0);
        return bytes;
    }

    /**
     * Packs the frame into the specified byte array at the given offset.
     *
     * @param bytes the destination byte array
     * @param offset the offset in the destination array
     * @throws NotSupportedException if the operation is not supported
     */
    public void toBytes(byte[] bytes, int offset) throws NotSupportedException {
        packFrame(bytes, offset);
    }

    /**
     * Writes the frame into the buffer.
     *
     * @param bytes the buffer to write
     * @param offset the write offset
     * @throws NotSupportedException if the frame cannot be written
     */
    public void packFrame(byte[] bytes, int offset) throws NotSupportedException {
        packHeader(bytes, offset);
        BufferTools.copyIntoByteBuffer(data, 0, data.length, bytes, offset + HEADER_LENGTH);
    }

    /**
     * Writes the frame header into the buffer.
     *
     * @param bytes the buffer to write
     * @param offset the write offset
     */
    private void packHeader(byte[] bytes, int offset) {
        try {
            BufferTools.stringIntoByteBuffer(id, 0, id.length(), bytes, offset);
        } catch (UnsupportedEncodingException e) { /* ignored */ }
        BufferTools.copyIntoByteBuffer(packDataLength(), 0, 4, bytes, offset + 4);
        BufferTools.copyIntoByteBuffer(packFlags(), 0, 2, bytes, offset + 8);
    }

    /**
     * Encodes the frame data length for the header.
     *
     * @return the encoded length bytes
     */
    protected byte[] packDataLength() {
        return BufferTools.packInteger(dataLength);
    }

    /**
     * Encodes the frame flags for the header.
     *
     * @return the two flag bytes
     */
    private byte[] packFlags() {
        byte[] bytes = new byte[2];
        bytes[0] = BufferTools.setBit(bytes[0], PRESERVE_TAG_BIT, preserveTag);
        bytes[0] = BufferTools.setBit(bytes[0], PRESERVE_FILE_BIT, preserveFile);
        bytes[0] = BufferTools.setBit(bytes[0], READ_ONLY_BIT, readOnly);
        bytes[1] = BufferTools.setBit(bytes[1], GROUP_BIT, group);
        bytes[1] = BufferTools.setBit(bytes[1], COMPRESSION_BIT, compression);
        bytes[1] = BufferTools.setBit(bytes[1], ENCRYPTION_BIT, encryption);
        bytes[1] = BufferTools.setBit(bytes[1], UNSYNCHRONISATION_BIT, unsynchronisation);
        bytes[1] = BufferTools.setBit(bytes[1], DATA_LENGTH_INDICATOR_BIT, dataLengthIndicator);
        return bytes;
    }

    /**
     * The frame's four-character ID, e.g. "TIT2".
     *
     * @return the frame ID
     */
    public String getId() {
        return id;
    }

    /**
     * Returns the length of the frame data in bytes.
     *
     * @return the data length
     */
    public int getDataLength() {
        return dataLength;
    }

    /**
     * Returns the total length of the frame including header in bytes.
     *
     * @return the total frame length
     */
    public int getLength() {
        return dataLength + HEADER_LENGTH;
    }

    /**
     * The raw frame payload bytes.
     *
     * @return the frame data
     */
    public byte[] getData() {
        return data;
    }

    /**
     * Replace the frame payload and update its length.
     *
     * @param data the new frame data
     */
    public void setData(byte[] data) {
        this.data = data;
        if (data == null) dataLength = 0;
        else dataLength = data.length;
    }

    /**
     * True if the frame carries a data length indicator.
     *
     * @return true if data length indicator is present
     */
    public boolean hasDataLengthIndicator() {
        return dataLengthIndicator;
    }

    /**
     * True if the frame data is compressed.
     *
     * @return true if frame is compressed
     */
    public boolean hasCompression() {
        return compression;
    }

    /**
     * True if the frame data is encrypted.
     *
     * @return true if frame is encrypted
     */
    public boolean hasEncryption() {
        return encryption;
    }

    /**
     * True if the frame belongs to a frame group.
     *
     * @return true if frame is grouped
     */
    public boolean hasGroup() {
        return group;
    }

    /**
     * True if the frame should survive tag edits (preserve file).
     *
     * @return true if preserve file flag is set
     */
    public boolean hasPreserveFile() {
        return preserveFile;
    }

    /**
     * True if the frame should survive tag discards (preserve tag).
     *
     * @return true if preserve tag flag is set
     */
    public boolean hasPreserveTag() {
        return preserveTag;
    }

    /**
     * True if the frame is marked read-only.
     *
     * @return true if frame is read-only
     */
    public boolean isReadOnly() {
        return readOnly;
    }

    /**
     * True if unsynchronisation is applied to the frame.
     *
     * @return true if unsynchronisation is enabled
     */
    public boolean hasUnsynchronisation() {
        return unsynchronisation;
    }

    @Override
    public int hashCode() {
        return 31
                        * Objects.hash(
                                compression,
                                dataLength,
                                dataLengthIndicator,
                                encryption,
                                group,
                                id,
                                preserveFile,
                                preserveTag,
                                readOnly,
                                unsynchronisation)
                + Arrays.hashCode(data);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null) return false;
        if (getClass() != obj.getClass()) return false;
        final ID3v2Frame other = (ID3v2Frame) obj;
        return Arrays.equals(data, other.data)
                && Objects.equals(compression, other.compression)
                && Objects.equals(dataLength, other.dataLength)
                && Objects.equals(dataLengthIndicator, other.dataLengthIndicator)
                && Objects.equals(encryption, other.encryption)
                && Objects.equals(group, other.group)
                && Objects.equals(id, other.id)
                && Objects.equals(preserveFile, other.preserveFile)
                && Objects.equals(preserveTag, other.preserveTag)
                && Objects.equals(readOnly, other.readOnly)
                && Objects.equals(unsynchronisation, other.unsynchronisation);
    }
}
