package com.mpatric.mp3agic;

/** An ID3v2.4 tag. */
public class ID3v24Tag extends AbstractID3v2Tag {

    /** Version string identifying this tag format. */
    public static final String VERSION = "4.0";

    /** Id of the recording-time frame, replacing the 2.3 date frames. */
    public static final String ID_RECTIME = "TDRC";

    /** Creates an empty tag. */
    public ID3v24Tag() {
        super();
        version = VERSION;
    }

    /**
     * Reads a tag from a buffer of a file's start.
     *
     * @param buffer the bytes to parse
     * @throws InvalidDataException if the tag data is corrupt
     * @throws NoSuchTagException if the header is not an ID3v2 tag
     * @throws UnsupportedTagException if the tag is not version 2.4
     */
    public ID3v24Tag(byte[] buffer)
            throws NoSuchTagException, UnsupportedTagException, InvalidDataException {
        super(buffer);
    }

    @Override
    protected void unpackFlags(byte[] buffer) {
        unsynchronisation = BufferTools.checkBit(buffer[FLAGS_OFFSET], UNSYNCHRONISATION_BIT);
        extendedHeader = BufferTools.checkBit(buffer[FLAGS_OFFSET], EXTENDED_HEADER_BIT);
        experimental = BufferTools.checkBit(buffer[FLAGS_OFFSET], EXPERIMENTAL_BIT);
        footer = BufferTools.checkBit(buffer[FLAGS_OFFSET], FOOTER_BIT);
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
        bytes[offset + FLAGS_OFFSET] =
                BufferTools.setBit(bytes[offset + FLAGS_OFFSET], FOOTER_BIT, footer);
    }

    @Override
    protected boolean useFrameUnsynchronisation() {
        return unsynchronisation;
    }

    @Override
    protected ID3v2Frame createFrame(byte[] buffer, int currentOffset) throws InvalidDataException {
        return new ID3v24Frame(buffer, currentOffset);
    }

    @Override
    protected ID3v2Frame createFrame(String id, byte[] data) {
        return new ID3v24Frame(id, data);
    }

    @Override
    public void setGenreDescription(String text) {
        ID3v2TextFrameData frameData =
                new ID3v2TextFrameData(useFrameUnsynchronisation(), new EncodedText(text));
        ID3v2FrameSet frameSet = getFrameSets().get(ID_GENRE);
        if (frameSet == null) {
            getFrameSets().put(ID_GENRE, frameSet = new ID3v2FrameSet(ID_GENRE));
        }
        frameSet.clear();
        frameSet.addFrame(createFrame(ID_GENRE, frameData.toBytes()));
    }

    /*
     * 'recording time' (TDRC) replaces the deprecated frames 'TDAT - Date', 'TIME - Time',
     * 'TRDA - Recording dates' and 'TYER - Year' in 4.0
     */

    /**
     * Reads the recording-time frame.
     *
     * @return the time, null if the frame is absent
     */
    public String getRecordingTime() {
        ID3v2TextFrameData frameData = extractTextFrameData(ID_RECTIME);
        if (frameData != null && frameData.getText() != null) return frameData.getText().toString();
        return null;
    }

    /**
     * Writes the recording-time frame.
     *
     * @param recTime the time to write, ignored when null or empty
     */
    public void setRecordingTime(String recTime) {
        if (recTime != null && recTime.length() > 0) {
            invalidateDataLength();
            ID3v2TextFrameData frameData =
                    new ID3v2TextFrameData(useFrameUnsynchronisation(), new EncodedText(recTime));
            addFrame(createFrame(ID_RECTIME, frameData.toBytes()), true);
        }
    }
}
